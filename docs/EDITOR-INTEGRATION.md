# Halo 官方编辑器集成 — 技术调研

> 调研时间：2026-09。结论：**集成完全可行，模式已被社区插件验证**。本文档沉淀调研结论与推荐设计，供后续实现参考。

> **已实现（2026-09）**：第五节推荐设计已落地，最终形态与 MVP 骨架基本一致：
>
> - `ui/src/editor/bilibili-player/index.ts` — `ExtensionBilibiliPlayer`（`Node.create`，name `bilibili-player`，`group: 'block'` + `atom: true`），attrs `bvid/cid/width/height` 全部序列化到 `data-*`，`parseHTML` 接管存量 `div[data-bilibili-player]` 嵌入代码（自动升级为可编辑节点），`renderHTML` 输出带真实 `aspect-ratio` 的 div+iframe；`getToolboxItems` / `getCommandMenuItems` 双入口齐备
> - `ui/src/editor/bilibili-player/BilibiliPlayerView.vue` — `VueNodeViewRenderer` NodeView：有 bvid 时渲染封面/标题/UP 主卡片（双击重开配置），空节点自动弹出 `VModal` 配置弹窗（解析 BV/链接 → 分 P 选择 → playurl 取分辨率 → `updateAttributes` 写回）
> - `ui/src/index.ts` 通过 `extensionPoints['default:editor:extension:create']` 注册，动态 import + try/catch 兜底（失败返回空数组，不阻塞编辑器）
> - 解析工具函数抽取至 `ui/src/utils/bilibili.ts`，与 HomeView 共享

## 一、历史考古：本项目曾有的编辑器扩展

仓库历史中存活过一个编辑器扩展 `ui/src/editor/BilibiliPlayerNode.ts`（新增于 `28b9cb8`，删除于 `1776832`，期间从未修改）：

- 纯 TipTap `Node.create({ name: 'bilibiliPlayer', group: 'block', atom: true })`，属性 `bvid/cid/title/origin`
- `renderHTML` 输出 `div[data-bilibili-player]` 包裹 iframe 指向 `/plugins/bilibili-player/embed`
- 通过 `extensionPoints['default:editor:extension:create']` 注册

**被移除的真实原因（diff 证据推断）**：它是未完成的脚手架——没有实现 `getCommandMenuItems` / `getToolboxItems` / `getToolbarItems` 任何一个插入入口，定义的 `insertBilibiliPlayer` 命令无任何调用方；attrs 未配 `parseHTML/renderHTML` 序列化（保存重载会丢属性）。同提交把主路线切换为「管理台生成 iframe 代码手动粘贴」，死代码随之清理。**被移除 ≠ 此路不通**，只是当时没接完。

## 二、编辑器扩展机制（已确认）

- **注册入口**：`ui/src/index.ts` 的 `definePlugin({ extensionPoints: { 'default:editor:extension:create': async () => [Extension] } })`，签名 `() => AnyExtension[] | Promise<AnyExtension[]>`，建议动态 import 懒加载
- **官方文档**：[默认编辑器扩展点](https://docs.halo.run/developer-guide/plugin/extension-points/ui/default-editor-extension-create)
- **Halo 独有钩子**（在 TipTap Node 的 `addOptions()` 返回，`ExtensionOptions` 类型见 `@halo-dev/richtext-editor`）：
  - `getToolboxItems({editor})` — 工具箱插入项（主要入口）
  - `getCommandMenuItems()` — 斜杠命令（`command` 里需先 `deleteRange(range)`，keywords 支持拼音）
  - `getToolbarItems` / `getBubbleMenu` / `getDraggableMenuItems`
- **构建块**：`@halo-dev/richtext-editor` 重导出 `Node`/`Extension`/`mergeAttributes`/`VueNodeViewRenderer`/`ToolboxItem` 等（插件应从这里导入，避免 TipTap 版本分裂）；内置 `ExtensionIframe`、`ExtensionVideo` 可参照
- **参考实现**：[halo-sigs/plugin-text-diagram](https://github.com/halo-sigs/plugin-text-diagram)（官方范本）；[chengzhongxue/plugin-thyuu-embed](https://github.com/chengzhongxue/plugin-thyuu-embed)（与本插件最相近：B站/抖音视频嵌入，编辑器 Node + 自定义元素存正文 + `TemplateHeadProcessor` 主题端注入水合脚本）

## 三、正文渲染链路（关键结论）

- **文章正文无服务端消毒**：HTML 原样存入 Snapshot，主题端 `th:utext` 原样输出；Jsoup Safelist 仅用于**评论**（`AbstractCommentService`）
- **编辑器侧唯一过滤点**：TipTap schema——不在已注册节点 schema 中的标签（裸 div 容器、script）**重编辑文章时会被剥离**；iframe 有内置节点（含粘贴识别规则、`isAllowedUri` 协议白名单防 XSS），自定义元素必须由扩展声明 `parseHTML` 才能存活
- **匿名访问无障碍**：`/plugins/bilibili-player/embed`（非 `/apis/` 前缀）落入安全链兜底 `permitAll`，读者无需登录
- **响应头无坑**：`X-Frame-Options: SAMEORIGIN`（同源 iframe 放行）、Halo 默认不下发 CSP、Referrer-Policy 不影响 embed 页自带的 `no-referrer` meta

## 四、嵌入形态对比

| 形态 | 存活性 | 编辑器体验 | 结论 |
|---|---|---|---|
| 裸 iframe 粘贴（现状） | ✅ | 有预览但无定制 UI | 保留作兼容 |
| div+iframe 无 schema 保护 | ⚠️ div 会被剥离 | 同左 | 避免 |
| `<video>` 直出 | ✅ | 一般 | 无法表达 DASH 双端同步 |
| **自定义元素 + 编辑器扩展**（thyuu 模式） | ✅ 需注册扩展 | 最佳：可视化卡片 + 弹窗选 BV | **推荐目标** |
| 短代码 + `ReactivePostContentHandler` 服务端渲染 | ✅ | 需自定义节点生成短代码 | 可选降级层 |

## 五、推荐设计（MVP）

**编辑器扩展** `ui/src/editor/bilibili-player/index.ts`：

```ts
import { Node, mergeAttributes, ToolboxItem } from '@halo-dev/richtext-editor'
import type { Editor, Range } from '@halo-dev/richtext-editor'
import { markRaw } from 'vue'

export const ExtensionBilibiliPlayer = Node.create({
  name: 'bilibili-player',
  group: 'block',
  atom: true,
  addAttributes() {
    return {
      // attrs 必须序列化到 data-*，否则保存/重载丢失（被删代码的缺陷）
      bvid: { default: '', parseHTML: el => el.getAttribute('data-bvid'),
              renderHTML: a => a.bvid ? { 'data-bvid': a.bvid } : {} },
      cid:  { default: '', parseHTML: el => el.getAttribute('data-cid'),
              renderHTML: a => a.cid ? { 'data-cid': a.cid } : {} },
    }
  },
  parseHTML() { return [{ tag: 'div[data-bilibili-player]' }] },
  renderHTML({ node, HTMLAttributes }) {
    const src = `/plugins/bilibili-player/embed?bvid=${node.attrs.bvid}&cid=${node.attrs.cid}`
    return ['div', mergeAttributes(HTMLAttributes, { 'data-bilibili-player': 'true' }),
      ['iframe', { src, style: 'width:100%;aspect-ratio:16/9;border:none;border-radius:8px',
                   allowfullscreen: 'true', allow: 'autoplay;encrypted-media', loading: 'lazy' }]]
  },
  addOptions() {
    return {
      ...this.parent?.(),
      getToolboxItems({ editor }: { editor: Editor }) {
        return [{ priority: 100, component: markRaw(ToolboxItem), props: {
          editor, icon: markRaw(BiliIcon), title: 'B站视频',
          action: () => openBvidDialog(editor),   // 弹窗收集 BV 号
        } }]
      },
      getCommandMenuItems() {
        return { priority: 100, icon: markRaw(BiliIcon), title: 'B站视频',
          keywords: ['bilibili', 'bzhan', 'b站'],
          command: ({ editor, range }: { editor: Editor; range: Range }) => {
            editor.chain().focus().deleteRange(range).run()
            openBvidDialog(editor)
          } }
      },
    }
  },
  // 可选：addNodeView() { return VueNodeViewRenderer(BilibiliPlayerView) }
  // 编辑器内渲染卡片（封面+标题），双击重新编辑 BV
})
```

**弹窗复用现有 API**：`openBvidDialog` 调用 `/plugins/bilibili-player/api/video/info`（解析 BV/多 P/横竖屏）+ `/video/playurl`（拿真实宽高比），确认后 `insertContent` 节点。管理台 HomeView 的解析逻辑可直接复用。

**配套（可选增强）**：
1. `TemplateHeadProcessor` 注入少量 CSS（响应式容器），不依赖主题样式
2. `ReactivePostContentHandler`（since 2.7.0）做存量文章短代码/旧格式 → iframe 的服务端兼容层
3. 保留「粘贴 iframe 代码」路径不动（内置 iframe 节点粘贴规则已支持），新旧两路并存

## 六、风险与未确认项

- ⚠️ Console 保存文章时是否额外跑 DOMPurify 未完全确认；但 TipTap schema 解析已是主过滤层，按 schema 注册即可存活
- ⚠️ 编辑器扩展由 Console 汇总所有启用插件的返回数组，扩展加载失败可能影响编辑器——实现时需保证懒加载 + try/catch 兜底
- ℹ️ README 中「粘贴到 HTML 视图」的措辞不准确——Halo 默认编辑器没有 HTML 源码视图，实际生效的是 iframe 扩展的粘贴识别规则

## 来源

- 本地：`git show 1776832`、`ui/node_modules/@halo-dev/ui-shared/dist/index.d.ts:1035`、`@halo-dev/richtext-editor/dist`
- [编辑器扩展点文档](https://docs.halo.run/developer-guide/plugin/extension-points/ui/default-editor-extension-create)、[ReactivePostContentHandler](https://docs.halo.run/developer-guide/plugin/extension-points/server/post-content)、[TemplateHeadProcessor](https://docs.halo.run/developer-guide/plugin/extension-points/server/template-head-processor)、[RBAC](https://docs.halo.run/developer-guide/plugin/security/rbac)
- [Halo iframe 扩展源码](https://raw.githubusercontent.com/halo-dev/halo/main/ui/packages/editor/src/extensions/iframe/index.ts)、[GHSA-x3rj-3x75-vw4g](https://github.com/halo-dev/halo/security/advisories/GHSA-x3rj-3x75-vw4g)、[WebServerSecurityConfig](https://raw.githubusercontent.com/halo-dev/halo/main/application/src/main/java/run/halo/app/infra/config/WebServerSecurityConfig.java)、[theme-earth post.html](https://raw.githubusercontent.com/halo-dev/theme-earth/main/src/post.html)
- [plugin-text-diagram](https://github.com/halo-sigs/plugin-text-diagram)、[plugin-thyuu-embed](https://github.com/chengzhongxue/plugin-thyuu-embed)
