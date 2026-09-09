import { resolve } from 'path';
import { defineConfig } from 'vite';
import Vue from '@vitejs/plugin-vue';
import Icons from 'unplugin-icons/vite';
import { HaloUIPluginBundlerKit } from '@halo-dev/ui-plugin-bundler-kit';
export default defineConfig({
    plugins: [Vue(), Icons({ compiler: 'vue3' }), HaloUIPluginBundlerKit()],
    resolve: {
        alias: {
            '@': resolve(__dirname, 'src'),
        },
    },
    build: {
        lib: {
            // Halo 按固定文件名 style.css 汇总加载插件样式，默认产物名 bilibili-player-ui.css 永远不会被加载；
            // entry 由 @halo-dev/ui-plugin-bundler-kit 在构建时注入，此处仅需覆盖 CSS 产物名
            cssFileName: 'style',
        },
    },
});
