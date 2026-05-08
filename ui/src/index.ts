import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'
import BilibiliPlayerNode from './editor/BilibiliPlayerNode'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/bilibili-player',
        name: 'BilibiliPlayer',
        component: () => import('./views/HomeView.vue'),
        meta: {
          title: 'B站播放器',
          searchable: true,
          menu: {
            name: 'B站播放器',
            group: '工具',
            icon: markRaw(IconPlug),
            priority: 10,
          },
        },
      },
    },
  ],
  extensionPoints: {
    'default:editor:extension:create': () => {
      return [BilibiliPlayerNode]
    },
  },
})
