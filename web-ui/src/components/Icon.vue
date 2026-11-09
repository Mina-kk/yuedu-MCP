<script>
import { h, defineComponent } from 'vue'

// 内部静态图标（只渲染本地常量，不渲染任何上游字符串），描边继承 currentColor。
const ICONS = {
  gear: [
    ['circle', { cx: '12', cy: '12', r: '3.2' }],
    ['path', { d: 'M12 2.8v2.6M12 18.6v2.6M4.5 4.5l1.9 1.9M17.6 17.6l1.9 1.9M2.8 12h2.6M18.6 12h2.6M4.5 19.5l1.9-1.9M17.6 6.4l1.9-1.9' }]
  ],
  refresh: [
    ['path', { d: 'M20.5 12a8.5 8.5 0 1 1-2.6-6.1' }],
    ['path', { d: 'M20.8 4.2v5.2h-5.2' }]
  ],
  search: [
    ['circle', { cx: '11', cy: '11', r: '7' }],
    ['path', { d: 'M20.2 20.2 16.4 16.4' }]
  ],
  x: [['path', { d: 'M18 6 6 18M6 6l12 12' }]],
  plus: [['path', { d: 'M12 5v14M5 12h14' }]],
  check: [['path', { d: 'M20 6.5 9.2 17.3 4 12.1' }]],
  copy: [
    ['rect', { x: '9', y: '9', width: '11.5', height: '11.5', rx: '2.6' }],
    ['path', { d: 'M15 5.5A2.5 2.5 0 0 0 12.5 3h-7A2.5 2.5 0 0 0 3 5.5v7A2.5 2.5 0 0 0 5.5 15' }]
  ],
  link: [
    ['path', { d: 'M10.4 13.6a4.6 4.6 0 0 0 6.5 0l2.3-2.3a4.6 4.6 0 0 0-6.5-6.5l-1.3 1.3' }],
    ['path', { d: 'M13.6 10.4a4.6 4.6 0 0 0-6.5 0l-2.3 2.3a4.6 4.6 0 0 0 6.5 6.5l1.3-1.3' }]
  ],
  zap: [['path', { d: 'M13.2 2.5 4.5 13.9h6L9.6 21.5 18.8 9.8h-6z' }]],
  edit: [
    ['path', { d: 'M12 20.5h8.5' }],
    ['path', { d: 'M16.6 3.6a2.2 2.2 0 0 1 3.1 3.1L7.2 19.2l-4.2 1 1-4.2z' }]
  ],
  trash: [
    ['path', { d: 'M3.5 6.2h17' }],
    ['path', { d: 'M8.8 6.2V4.3a1.3 1.3 0 0 1 1.3-1.3h3.8a1.3 1.3 0 0 1 1.3 1.3v1.9' }],
    ['path', { d: 'M5.4 6.2l1 13.3a1.6 1.6 0 0 0 1.6 1.5h8a1.6 1.6 0 0 0 1.6-1.5l1-13.3' }],
    ['path', { d: 'M10.2 10.5v6.4M13.8 10.5v6.4' }]
  ],
  upload: [
    ['path', { d: 'M12 16.5V4' }],
    ['path', { d: 'M7 9l5-5 5 5' }],
    ['path', { d: 'M4 17v3.5h16V17' }]
  ],
  download: [
    ['path', { d: 'M12 4v12.5' }],
    ['path', { d: 'M7 11.5l5 5 5-5' }],
    ['path', { d: 'M4 20.5h16' }]
  ],
  'chevron-down': [['path', { d: 'M6 9.2 12 15.2 18 9.2' }]],
  'chevron-left': [['path', { d: 'M15 5.5 8.5 12 15 18.5' }]],
  'chevron-right': [['path', { d: 'M9 5.5 15.5 12 9 18.5' }]],
  sun: [
    ['circle', { cx: '12', cy: '12', r: '4' }],
    ['path', { d: 'M12 2.2v2.2M12 19.6v2.2M4.3 4.3l1.6 1.6M18.1 18.1l1.6 1.6M2.2 12h2.2M19.6 12h2.2M4.3 19.7l1.6-1.6M18.1 5.9l1.6-1.6' }]
  ],
  moon: [['path', { d: 'M20.8 14.6A8.8 8.8 0 0 1 9.4 3.2 8.8 8.8 0 1 0 20.8 14.6z' }]],
  auto: [
    ['circle', { cx: '12', cy: '12', r: '8.6' }],
    ['path', { d: 'M12 3.4v17.2' }],
    ['path', { d: 'M12 3.4a8.6 8.6 0 0 1 0 17.2z', fill: 'currentColor', stroke: 'none' }]
  ],
  server: [
    ['rect', { x: '3', y: '4', width: '18', height: '7', rx: '2.2' }],
    ['rect', { x: '3', y: '13', width: '18', height: '7', rx: '2.2' }],
    ['path', { d: 'M7 7.5h.01M7 16.5h.01' }]
  ],
  book: [
    ['path', { d: 'M4 5.2A2.2 2.2 0 0 1 6.2 3H19v15.6H6.2A2.2 2.2 0 0 0 4 20.8z' }],
    ['path', { d: 'M4 18.6A2.2 2.2 0 0 1 6.2 16.4H19' }]
  ],
  sparkles: [
    ['path', { d: 'M12 3.2l1.7 4.6 4.6 1.7-4.6 1.7L12 15.8l-1.7-4.6L5.7 9.5l4.6-1.7z' }],
    ['path', { d: 'M18.4 15.1l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z' }]
  ],
  shield: [
    ['path', { d: 'M12 2.8 20 6v6.1c0 4.6-3.3 7.9-8 9.1-4.7-1.2-8-4.5-8-9.1V6z' }],
    ['path', { d: 'M8.8 12.2 11.2 14.6 15.4 10.4' }]
  ],
  list: [
    ['path', { d: 'M8.5 6.5h12M8.5 12h12M8.5 17.5h12' }],
    ['path', { d: 'M4 6.5h.01M4 12h.01M4 17.5h.01' }]
  ],
  clock: [
    ['circle', { cx: '12', cy: '12', r: '8.5' }],
    ['path', { d: 'M12 7.5V12l3 2' }]
  ],
  alert: [
    ['circle', { cx: '12', cy: '12', r: '8.5' }],
    ['path', { d: 'M12 7.5v5.5' }],
    ['circle', { cx: '12', cy: '16.6', r: '1', fill: 'currentColor', stroke: 'none' }]
  ],
  play: [['path', { d: 'M7 4.8 19 12 7 19.2z' }]],
  stop: [['rect', { x: '6', y: '6', width: '12', height: '12', rx: '2.4' }]],
  'external-link': [
    ['path', { d: 'M14 4h6v6' }],
    ['path', { d: 'M20 4 11 13' }],
    ['path', { d: 'M18 14.5V18a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h3.5' }]
  ],
  code: [
    ['path', { d: 'M9 6.5 3.5 12 9 17.5' }],
    ['path', { d: 'M15 6.5 20.5 12 15 17.5' }]
  ],
  dot: [['circle', { cx: '12', cy: '12', r: '8.5' }]]
}

export default defineComponent({
  name: 'AppIcon',
  props: {
    name: { type: String, default: 'dot' },
    size: { type: [Number, String], default: 20 }
  },
  setup(props) {
    return () => {
      const parts = ICONS[props.name] || ICONS.dot
      return h(
        'svg',
        {
          class: 'ico',
          width: String(props.size),
          height: String(props.size),
          viewBox: '0 0 24 24',
          'aria-hidden': 'true',
          focusable: 'false'
        },
        parts.map(([tag, attrs]) => h(tag, attrs))
      )
    }
  }
})
</script>
