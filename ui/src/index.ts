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
})
