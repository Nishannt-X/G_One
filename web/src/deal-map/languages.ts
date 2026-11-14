import type { Theme } from '../lib/theme'

export type LanguageCode = 'kn' | 'bn' | 'hi' | 'mr' | 'ta' | 'en'

/**
 * One colour per language for the call thread, picked to stay apart from each other and readable on the page in both
 * themes. The map always prints the language name too, so colour is never the only cue.
 */
export const LANGUAGES: Record<LanguageCode, { name: string; light: string; dark: string }> = {
  kn: { name: 'Kannada', light: '#0f766e', dark: '#5eead4' },
  bn: { name: 'Bengali', light: '#7c3aed', dark: '#c4b5fd' },
  hi: { name: 'Hindi', light: '#be185d', dark: '#f9a8d4' },
  mr: { name: 'Marathi', light: '#a16207', dark: '#fcd34d' },
  ta: { name: 'Tamil', light: '#0369a1', dark: '#7dd3fc' },
  en: { name: 'English', light: '#475569', dark: '#cbd5e1' },
}

export function languageColour(code: LanguageCode, theme: Theme): string {
  return LANGUAGES[code][theme]
}

export function languageName(code: LanguageCode): string {
  return LANGUAGES[code].name
}

/** "Kannada", "Kannada ↔ Bengali" (spoken across the call) or "Bengali → Hindi" (a switch). */
export function stepLanguages(step: { langs: readonly LanguageCode[]; join?: 'across' | 'switch' }): string {
  return step.langs.map(languageName).join(step.join === 'switch' ? ' → ' : step.join === 'across' ? ' ↔ ' : ', ')
}
