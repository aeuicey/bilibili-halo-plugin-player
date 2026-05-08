import { Node } from '@tiptap/core'

export interface BilibiliPlayerOptions {
  bvid: string
  cid: string
  title?: string
  origin: string
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    bilibiliPlayer: {
      insertBilibiliPlayer: (options: BilibiliPlayerOptions) => ReturnType
    }
  }
}

const BilibiliPlayerNode = Node.create({
  name: 'bilibiliPlayer',

  group: 'block',

  atom: true,

  addAttributes() {
    return {
      bvid: { default: '' },
      cid: { default: '' },
      title: { default: '' },
      origin: { default: '' },
    }
  },

  parseHTML() {
    return [
      {
        tag: 'div[data-bilibili-player]',
      },
    ]
  },

  renderHTML({ HTMLAttributes }) {
    const { bvid, cid, origin } = HTMLAttributes
    const src = `${origin || ''}/plugins/bilibili-player/embed?bvid=${bvid}&cid=${cid}`
    return [
      'div',
      {
        'data-bilibili-player': 'true',
        style:
          'position:relative;width:100%;max-width:100%;aspect-ratio:16/9;border-radius:8px;overflow:hidden;margin:16px 0',
      },
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

  addCommands() {
    return {
      insertBilibiliPlayer:
        (options: BilibiliPlayerOptions) =>
        ({ commands }) => {
          return commands.insertContent({
            type: this.name,
            attrs: {
              bvid: options.bvid,
              cid: options.cid,
              title: options.title || '',
              origin: options.origin || '',
            },
          })
        },
    }
  },
})

export default BilibiliPlayerNode
