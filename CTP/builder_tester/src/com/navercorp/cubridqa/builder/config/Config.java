package com.navercorp.cubridqa.builder.config;

import com.navercorp.cubridqa.builder.BuilderConfig;

/**
 * Config alias for BuilderConfig to maintain compatibility with refactored components.
 */
public class Config extends BuilderConfig {
    public Config(String configFile) throws java.io.IOException {
        super(configFile);
    }
}