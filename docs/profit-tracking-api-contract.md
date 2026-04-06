# Profit Tracking API — Server Contract

Base path: `POST/GET https://api.flipvault.app/api/plugin/profit-tracking/...`

All endpoints require:
```
Authorization: Bearer <jwtToken>
```

The JWT is issued by the FlipVault login flow. The server must resolve the authenticated user from it on every request.

---

## Core Concept: Integer Account IDs

The client uses **integer account IDs** (int32) to identify OSRS accounts. These are NOT the Better Auth user UUIDs — they are a separate stable integer per OSRS character name.

The server must maintain a mapping:
```
osrs_username (string) → account_id (int32, unique, stable)
```

A simple implementation: store an auto-incrementing integer per OSRS username tied to the user. The client caches these IDs and uses them everywhere — in flip records, in delta polling, in the binary wire format. **These IDs must never change for a given username.**

---

## Endpoint 1 — `GET /profit-tracking/rs-account-names`

Returns the authenticated user's OSRS account name → integer account ID mappings.

### Response

```
Content-Type: application/json
```

```json
{
  "Zezima": 1001,
  "IronZezima": 1002
}
```

- Keys are OSRS display names (case-sensitive, as bound to the API key via `boundTo`)
- Values are stable int32 account IDs
- Return an empty object `{}` if the user has no linked accounts yet

### Client behaviour

The client polls this once at startup (with exponential backoff on failure), then again after login. Until it gets at least one account, it will NOT poll for flips. This is the **critical unlock** — everything else depends on it.

---

## Endpoint 2 — `POST /profit-tracking/client-transactions`

Called every time the player completes a GE transaction (partial fills included). The server should store the transactions, match BUY+SELL pairs into flips, and return any newly created or updated `FlipV2` records.

### Request

```
Content-Type: application/json
Accept: application/x-bytes
Query param: ?display_name=<url-encoded OSRS name>
```

Body is a JSON array of transaction objects:

```json
[
  {
    "id":                      "550e8400-e29b-41d4-a716-446655440000",
    "item_id":                 4151,
    "price":                   2500000,
    "quantity":                1,
    "box_id":                  3,
    "amount_spent":            2500000,
    "time":                    1743885000,
    "copilot_price_used":      true,
    "was_copilot_suggestion":  true,
    "consistent_previous_offer": true,
    "login":                   false
  }
]
```

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID string | Stable client-generated UUID per transaction. Use for idempotency — if you've seen this UUID before, skip insert but still return the current flip state. |
| `item_id` | int | OSRS item ID |
| `price` | int | The offer price (per unit) |
| `quantity` | int | **Positive = BUY, negative = SELL.** The sign encodes the transaction type. |
| `box_id` | int | GE slot index (0–7) |
| `amount_spent` | int | For buys: total gold spent. For sells: total gross gold received (before GE tax — the client applies tax when computing profit). |
| `time` | int | Unix epoch seconds |
| `copilot_price_used` | bool | Whether the FV suggested price was used |
| `was_copilot_suggestion` | bool | Whether this offer originated from an FV suggestion |
| `consistent_previous_offer` | bool | Internal consistency flag; safe to store but not critical |
| `login` | bool | True if the transaction was inferred during the login burst window; less reliable |

### Flip matching logic

After storing each transaction, attempt to match BUY + SELL pairs for the same `(user, item_id)`:

1. Find the oldest unmatched BUY for `(account_id, item_id)` with remaining quantity
2. Match against the incoming SELL quantity
3. Create/update a `FlipV2` record with the matched amounts
4. A flip is `FINISHED` when `closed_quantity >= opened_quantity`

GE tax formula (applies to the sell side):
```
tax = 0   if item is tax-exempt (see list below)
tax = min(floor(sell_price * 0.02), 5_000_000)  otherwise
profit = (sell_price - tax) * quantity - buy_price * quantity
```

Tax-exempt item IDs: `8011, 365, 2309, 882, 806, 1891, 8010, 1755, 28824, 2140, 2142, 8009, 5325, 1785, 2347, 347, 884, 807, 28790, 379, 8008, 355, 2327, 558, 1733, 13190, 233, 351, 5341, 2552, 329, 8794, 5329, 5343, 1735, 315, 952, 886, 808, 8013, 361, 8007, 5331`

### Response

```
Content-Type: application/octet-stream
```

Binary encoded list of `FlipV2` records that were created or updated as a result of this request. See **FlipV2 Binary Format** below.

If there are no changes (e.g. all transactions were already known), return:
```
[int32 BE: 0]   // = 4 bytes, record count of 0
```

**Do not return empty bytes (`0` length) — the client will throw on less than 4 bytes.**

---

## Endpoint 3 — `POST /profit-tracking/client-flips-delta`

Long-polling delta sync. The client calls this every ~5 seconds to pick up any flip changes (including ones produced by other devices or by server-side reprocessing). This keeps the client's in-memory flip state in sync.

### Request

```
Content-Type: application/json
Accept: application/x-bytes
```

```json
{
  "account_id_time": {
    "1001": 1743885000,
    "1002": 0
  }
}
```

`account_id_time` maps each integer account ID to the last `updated_time` (Unix epoch seconds) the client already has for that account. Return only flips with `updated_time > value`. An account with value `0` means "send everything."

### Response

```
Content-Type: application/octet-stream
```

```
[int32 BE: server_timestamp]   // current Unix epoch seconds (4 bytes)
[FlipV2 binary list]           // 0 or more 84-byte records
```

The client stores the returned `server_timestamp` and sends it back on the next poll, so only genuine changes are transferred.

If there are no new flips, return:
```
[int32 BE: current_timestamp][int32 BE: 0]   // 8 bytes total
```

---

## FlipV2 Binary Format

Each flip record is exactly **84 bytes**, big-endian.

```
Offset  Size  Type    Field
──────────────────────────────────────────────────────────────
 0      16    bytes   id (UUID: 8 bytes most-sig + 8 bytes least-sig)
16       4    int32   account_id
20       4    int32   item_id
24       4    int32   opened_time    (Unix epoch seconds)
28       4    int32   opened_quantity
32       8    int64   spent          (total gold spent on buy side)
40       4    int32   closed_time    (Unix epoch seconds; 0 if not yet closed)
44       4    int32   closed_quantity
48       8    int64   received_post_tax  (gold received after GE tax)
56       8    int64   profit         (net profit in GP)
64       8    int64   tax_paid       (GE tax deducted)
72       4    int32   status         (ordinal: 0=BUYING, 1=SELLING, 2=FINISHED)
76       4    int32   updated_time   (Unix epoch seconds — used for delta filtering)
80       4    int32   deleted        (0 = live, 1 = soft-deleted)
──────────────────────────────────────────────────────────────
Total: 84 bytes
```

FlipV2 list binary format (used in both endpoint responses):
```
[int32 BE: record_count][record_0][record_1]...[record_N]
```
Total bytes: `4 + (record_count * 84)`

### FlipStatus ordinals

| Value | Meaning |
|-------|---------|
| 0 | `BUYING` — buy side open, no sell yet |
| 1 | `SELLING` — partially or fully matched with a sell |
| 2 | `FINISHED` — fully closed |

### Key field semantics

- `opened_quantity` / `opened_quantity`: from the buy transaction
- `closed_quantity`: cumulative quantity matched to sell transactions so far
- `spent`: total gold spent on the buy side (sum of `amount_spent` across all matched buy transactions)
- `received_post_tax`: total gold received on the sell side, already net of GE tax
- `profit`: `received_post_tax - spent` (for the matched quantity)
- `tax_paid`: GE tax deducted from sell proceeds
- `deleted = 1`: the client will remove the flip from its local state

---

## FlipsDeltaResult binary format

Full response frame for `client-flips-delta`:
```
[int32 BE: server_timestamp]   // 4 bytes
[int32 BE: record_count]       // 4 bytes
[record_0 ... record_N]        // record_count × 84 bytes
```

---

## Auth errors

| HTTP code | Meaning | Client behaviour |
|-----------|---------|-----------------|
| 401 | JWT invalid or expired | Client clears session and shows login screen |
| 403 | Valid JWT but insufficient access | Client logs error, backs off |
| 4xx | Bad request | Client logs, does not retry that payload |
| 5xx | Server error | Client retries with exponential backoff (cap ~45s) |

---

## Idempotency notes

- `client-transactions`: use the transaction `id` UUID as an idempotency key. If already processed, return the current flip state for those items without re-inserting.
- `client-flips-delta`: purely read-only and safe to call repeatedly.

---

## Implementation priority

1. **`rs-account-names`** first — nothing else works until the client gets account IDs
2. **`client-transactions`** second — stores transactions and produces flip records
3. **`client-flips-delta`** third — keeps the client's running flip history in sync

The existing `pluginTransactions` and `pluginFlips` tables in the DB already have the right shape for storing this data. The main additions needed are:
- An `osrs_accounts` table mapping `(user_id, osrs_username) → integer account_id`
- Flip matching logic in `client-transactions`
- Binary serialisation of `FlipV2` records in both endpoints

---

## Discord Leaderboard & Role Assignment

Once flip records are being stored, the server has everything needed to power a Discord leaderboard and auto-assign roles.

### Data available per user

From the `pluginFlips` table (populated by `client-transactions`):
- `profit` per `FlipV2` record — sum across all flips for a user's total lifetime GP profit
- `user_id` — links to the Better Auth user, which has `discord_id` from the Discord OAuth login flow

### Suggested role tiers (adjust thresholds as desired)

| Role | Cumulative GP profit |
|------|---------------------|
| Bronze Flipper | 10,000,000 (10M) |
| Silver Flipper | 100,000,000 (100M) |
| Gold Flipper | 1,000,000,000 (1B) |
| Dragon Flipper | 10,000,000,000 (10B) |

### Server-side implementation

1. **Profit aggregation query** — run periodically (e.g. cron every 15 minutes):
   ```sql
   SELECT user_id, SUM(profit) AS total_profit
   FROM plugin_flips
   WHERE status = 'FINISHED'
   GROUP BY user_id
   ```

2. **Map to Discord** — join with the auth/account table to get `discord_id` per `user_id`. Users without a linked Discord account are skipped.

3. **Role assignment** — use the Discord REST API (`PUT /guilds/{guild_id}/members/{user_id}/roles/{role_id}`) with a bot token that has `MANAGE_ROLES`. Remove lower-tier roles when a user crosses into a higher tier.

4. **Leaderboard endpoint** (optional, for a public leaderboard in Discord or on the site):
   ```
   GET /api/leaderboard?limit=100
   ```
   Returns top N users with fields: `discord_username`, `discord_avatar`, `total_profit`, `flip_count`.
   This endpoint should NOT require auth and can be cached for 5 minutes.

### Notes

- `discord_id` is already stored during the Discord OAuth login flow — no additional client changes are needed.
- Profit values are in GP (integer). A user with no finished flips has `total_profit = 0` and should not appear on the leaderboard.
- The `deleted = 1` flag on a `FlipV2` record means the flip was cancelled/reversed — exclude these from profit sums.
