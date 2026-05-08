package com.halo.bilibiliplayer;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class BilibiliPlayerPlugin extends BasePlugin {

    public BilibiliPlayerPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
