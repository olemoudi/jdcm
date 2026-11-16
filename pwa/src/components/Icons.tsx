import type { SVGProps } from 'react'
import type { SportKind } from '../lib/data'

type P = SVGProps<SVGSVGElement> & { size?: number }

function Svg({ size = 24, children, ...rest }: P) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" {...rest}>
      {children}
    </svg>
  )
}

/** iOS's share symbol: a square with an arrow out of its top. */
export const IosShare = (p: P) => (
  <Svg {...p}><path d="M12 3v12" /><path d="M8 7l4-4 4 4" /><path d="M7 10H6a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-9a1 1 0 0 0-1-1h-1" /></Svg>
)
/** "Add to Home Screen": a plus in a rounded square. */
export const AddSquare = (p: P) => (
  <Svg {...p}><rect x="4" y="4" width="16" height="16" rx="3.5" /><path d="M12 8.5v7M8.5 12h7" /></Svg>
)
/** Safari's "···" menu (iOS 26 hides Share in it). */
export const Ellipsis = (p: P) => (
  <Svg {...p}><circle cx="12" cy="12" r="9" /><circle cx="8" cy="12" r="0.9" fill="currentColor" /><circle cx="12" cy="12" r="0.9" fill="currentColor" /><circle cx="16" cy="12" r="0.9" fill="currentColor" /></Svg>
)
export const DotsVertical = (p: P) => (
  <Svg {...p}><circle cx="12" cy="5" r="1" fill="currentColor" /><circle cx="12" cy="12" r="1" fill="currentColor" /><circle cx="12" cy="19" r="1" fill="currentColor" /></Svg>
)
export const Compass = (p: P) => (
  <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M15.5 8.5l-2 5-5 2 2-5z" /></Svg>
)
export const Directions = (p: P) => (
  <Svg {...p}><path d="M11.3 2.7a1 1 0 0 1 1.4 0l8.6 8.6a1 1 0 0 1 0 1.4l-8.6 8.6a1 1 0 0 1-1.4 0l-8.6-8.6a1 1 0 0 1 0-1.4z" /><path d="M9 14v-2.5a1.5 1.5 0 0 1 1.5-1.5H15" /><path d="M13 8l2 2-2 2" /></Svg>
)
export const CalendarAdd = (p: P) => (
  <Svg {...p}><rect x="3.5" y="5" width="17" height="15.5" rx="2.5" /><path d="M3.5 9.5h17M8 3v4M16 3v4M12 12.5v5M9.5 15h5" /></Svg>
)
export const Share = (p: P) => (
  <Svg {...p}><circle cx="18" cy="5.5" r="2.5" /><circle cx="6" cy="12" r="2.5" /><circle cx="18" cy="18.5" r="2.5" /><path d="M8.2 10.8l7.6-4.1M8.2 13.2l7.6 4.1" /></Svg>
)
export const Place = (p: P) => (
  <Svg {...p}><path d="M12 21s-6.5-6.2-6.5-11a6.5 6.5 0 0 1 13 0c0 4.8-6.5 11-6.5 11z" /><circle cx="12" cy="10" r="2.3" /></Svg>
)
export const Clock = (p: P) => (
  <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 7.5V12l3 2" /></Svg>
)
export const Info = (p: P) => (
  <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 11v5.5M12 7.6v.1" /></Svg>
)
export const ChevronLeft = (p: P) => <Svg {...p}><path d="M15 5l-7 7 7 7" /></Svg>
export const ChevronRight = (p: P) => <Svg {...p}><path d="M9 5l7 7-7 7" /></Svg>
export const ChevronDown = (p: P) => <Svg {...p}><path d="M5 9l7 7 7-7" /></Svg>
export const ChevronUp = (p: P) => <Svg {...p}><path d="M5 15l7-7 7 7" /></Svg>
export const Close = (p: P) => <Svg {...p}><path d="M6 6l12 12M18 6L6 18" /></Svg>
export const Plus = (p: P) => <Svg {...p}><path d="M12 5v14M5 12h14" /></Svg>
export const Refresh = (p: P) => (
  <Svg {...p}><path d="M20 11a8 8 0 1 0-2.3 5.7" /><path d="M20 4.5V11h-6.5" /></Svg>
)
export const Search = (p: P) => <Svg {...p}><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /></Svg>
export const Check = (p: P) => <Svg {...p}><path d="M5 12.5l4.5 4.5L19 7.5" /></Svg>
export const Pencil = (p: P) => <Svg {...p}><path d="M4 20h4L19 9l-4-4L4 16z" /><path d="M13.5 6.5l4 4" /></Svg>
export const EventBusy = (p: P) => (
  <Svg {...p}><rect x="3.5" y="5" width="17" height="15.5" rx="2.5" /><path d="M3.5 9.5h17M8 3v4M16 3v4M9.5 13l5 5M14.5 13l-5 5" /></Svg>
)
export const External = (p: P) => <Svg {...p}><path d="M14 4h6v6M20 4l-9 9" /><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></Svg>

/** Bottom tab bar. */
export const TabMatches = (p: P) => (
  <Svg {...p}><rect x="3.5" y="5" width="17" height="15.5" rx="2.5" /><path d="M3.5 9.5h17M8 3v4M16 3v4" /><circle cx="12" cy="15" r="2.6" /></Svg>
)
export const TabTables = (p: P) => (
  <Svg {...p}><path d="M4 6h16M4 12h16M4 18h16" /><path d="M8 4v16" /></Svg>
)
export const TabSettings = (p: P) => (
  <Svg {...p}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></Svg>
)

/** One small drawing per sport (the app's sportIcon). */
export function SportIcon({ kind, ...p }: P & { kind: SportKind }) {
  switch (kind) {
    case 'BASKETBALL':
      return <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3v18M5.6 5.6c3 2.6 3 10.2 0 12.8M18.4 5.6c-3 2.6-3 10.2 0 12.8" /></Svg>
    case 'VOLLEYBALL':
      return <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 3c-1.5 4 0 8 4.5 10.5M3.5 9.5c4-.5 7.5 1.5 8.5 6.5M20.5 14c-3.5 3-8 3.5-12 1" /></Svg>
    case 'FUTSAL':
      return <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 8.2l3.2 2.3-1.2 3.8h-4l-1.2-3.8z" /><path d="M12 8.2V3.5M15.2 10.5l4.3-1.4M14 14.3l2.6 3.7M10 14.3l-2.6 3.7M8.8 10.5L4.5 9.1" /></Svg>
    default:
      return <Svg {...p}><circle cx="12" cy="12" r="9" /></Svg>
  }
}
