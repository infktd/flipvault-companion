package com.flippingcopilot.rs;

import com.flippingcopilot.config.FlipVaultConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class FVConfigRS extends ReactiveStateImpl<FlipVaultConfig> {

    @Inject
    public FVConfigRS(FlipVaultConfig config) {
        super(config);
        registerListener(current -> log.debug("FVConfigRS changed"));
    }
}
