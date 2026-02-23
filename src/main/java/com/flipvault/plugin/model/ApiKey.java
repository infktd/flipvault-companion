package com.flipvault.plugin.model;

import lombok.Data;

@Data
public class ApiKey {
    private String id;
    private String label;
    private String maskedKey;
    private String boundTo;
    private String createdAt;
}
