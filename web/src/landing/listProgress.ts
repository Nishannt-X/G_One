/**
 * How far a list of points has been read: point `i` counts as reached once its edge (`edges[i]`, a position in the
 * window) rises past `line`, and between two points the value runs smoothly from `i` to `i + 1`.
 *
 * The first point counts as reached from the start (0), even before its edge reaches the line, so the list never shows
 * an empty column beside a scene that already shows its first pane.
 */
export function listProgress(edges: number[], line: number): number {
  const last = edges.length - 1
  if (last < 0 || edges[0] > line) return 0
  let i = 0
  while (i < last && edges[i + 1] <= line) i++
  if (i === last) return last
  return i + (line - edges[i]) / Math.max(1, edges[i + 1] - edges[i])
}
