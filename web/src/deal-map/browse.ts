/** The state of the deal browser: which deal is open, and which list item gets focus back after "All deals". */
export interface BrowseState {
  selected: string | null
  lastOpened: string | null
}
export type BrowseAction = { type: 'select'; id: string } | { type: 'back' }

export function browse(state: BrowseState, action: BrowseAction): BrowseState {
  if (action.type === 'select') return { selected: action.id, lastOpened: action.id }
  return { selected: null, lastOpened: state.selected ?? state.lastOpened }
}

export const listItemId = (id: string) => `deal-item-${id}`
