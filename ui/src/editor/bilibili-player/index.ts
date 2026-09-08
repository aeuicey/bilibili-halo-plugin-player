import {
  mergeAttributes,
  Node,
  ToolboxItem,
  VueNodeViewRenderer,
} from '@halo-dev/richtext-editor'
import type {
  CommandMenuItemType,
  Editor,
  ExtensionOptions,
  Range,
  ToolboxItemType,
} from '@halo-dev/richtext-editor'
import { markRaw } from 'vue'
import RiBilibiliLine from '~icons/ri/bilibili-line'
import BilibiliPlayerView from './BilibiliPlayerView.vue'

export const BILIBILI_PLAYER_NODE_NAME = 'bilibili-player'

// 编辑器内插入空节点（随后由 NodeView 弹窗补全 bvid/cid），并追加空段落便于继续输入
function insertBilibiliPlayer(editor: Editor) {
  editor
    .chain()
    .focus()
    .insertContent([{ type: BILIBILI_PLAYER_NODE_NAME }, { type: 'paragraph' }])
    .run()
}

export const ExtensionBilibiliPlayer = Node.create<ExtensionOptions>({
  name: BILIBILI_PLAYER_NODE_NAME,
  group: 'block',
  atom: true,

  addAttributes() {
    // attrs 必须序列化到 data-*，否则保存/重载后丢失（历史教训）
    return {
      bvid: {
        default: '',
        parseHTML: (element) => element.getAttribute('data-bvid') || '',
        renderHTML: (attributes) => (attributes.bvid ? { 'data-bvid': attributes.bvid } : {}),
      },
      cid: {
        default: '',
        parseHTML: (element) => element.getAttribute('data-cid') || '',
        renderHTML: (attributes) => (attributes.cid ? { 'data-cid': attributes.cid } : {}),
      },
      width: {
        default: 0,
        parseHTML: (element) => Number(element.getAttribute('data-width')) || 0,
        renderHTML: (attributes) =>
          Number(attributes.width) > 0 ? { 'data-width': attributes.width } : {},
      },
      height: {
        default: 0,
        parseHTML: (element) => Number(element.getAttribute('data-height')) || 0,
        renderHTML: (attributes) =>
          Number(attributes.height) > 0 ? { 'data-height': attributes.height } : {},
      },
    }
  },

  parseHTML() {
    // 接管嵌入代码生成器产出的 div，存量文章粘贴的嵌入代码自动升级为可编辑节点
    return [{ tag: 'div[data-bilibili-player]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    const { bvid, cid, width, height } = node.attrs
    const ratio = Number(width) > 0 && Number(height) > 0 ? `${width}/${height}` : '16/9'
    const src = `/plugins/bilibili-player/embed?bvid=${encodeURIComponent(bvid || '')}&cid=${encodeURIComponent(cid || '')}`
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-bilibili-player': 'true',
        style: `position:relative;width:100%;aspect-ratio:${ratio};border-radius:8px;overflow:hidden;margin:16px 0;background:#000`,
      }),
      [
        'iframe',
        {
          src,
          style: 'position:absolute;top:0;left:0;width:100%;height:100%;border:none',
          allowfullscreen: 'true',
          allow: 'autoplay;encrypted-media',
          loading: 'lazy',
        },
      ],
    ]
  },

  addNodeView() {
    return VueNodeViewRenderer(BilibiliPlayerView)
  },

  addOptions(): ExtensionOptions {
    return {
      ...this.parent?.(),
      getToolboxItems({ editor }: { editor: Editor }): ToolboxItemType[] {
        return [
          {
            priority: 100,
            component: markRaw(ToolboxItem),
            props: {
              editor,
              icon: markRaw(RiBilibiliLine),
              title: 'B站视频',
              description: '插入哔哩哔哩视频',
              action: () => insertBilibiliPlayer(editor),
            },
          },
        ]
      },
      getCommandMenuItems(): CommandMenuItemType {
        return {
          priority: 100,
          icon: markRaw(RiBilibiliLine),
          title: 'B站视频',
          keywords: ['bilibili', 'bzhan', 'b站', 'shipin', 'sp'],
          command: ({ editor, range }: { editor: Editor; range: Range }) => {
            editor.chain().focus().deleteRange(range).run()
            insertBilibiliPlayer(editor)
          },
        }
      },
    }
  },
})
