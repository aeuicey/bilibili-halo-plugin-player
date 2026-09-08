import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'

const HomeView = () => import('./views/HomeView.vue')

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/bilibili-player',
        name: 'BilibiliPlayer',
        component: HomeView,
        meta: {
          title: 'B 站播放器',
          searchable: true,
          menu: {
            name: 'B 站播放器',
            group: '工具',
            icon: markRaw(IconPlug),
            priority: 10,
          },
        },
      },
    },
    {
      parentName: 'PluginRoot',
      route: {
        path: '/plugins/bilibili-player',
        children: [
          {
            path: '',
            name: 'BilibiliPlayerSetting',
            component: HomeView,
            meta: {
              title: 'B 站播放器配置',
              searchable: true,
              permissions: ['*'],
              menu: {
                name: '配置',
                priority: 0,
              },
            },
          },
        ],
      },
    },
  ],
  extensionPoints: {
    'default:editor:extension:create': async () => {
      // 懒加载 + 兜底：扩展加载失败不应阻塞编辑器
      try {
        const { ExtensionBilibiliPlayer } = await import('./editor/bilibili-player')
        return [ExtensionBilibiliPlayer]
      } catch (e) {
        console.error('[bilibili-player] 加载编辑器扩展失败', e)
        return []
      }
    },
  },
})
