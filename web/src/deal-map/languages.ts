export type LanguageCode = 'kn' | 'bn' | 'hi' | 'mr' | 'ta' | 'te' | 'en'

export const LANGUAGES: Record<LanguageCode, { name: string }> = {
  kn: { name: 'Kannada' },
  bn: { name: 'Bengali' },
  hi: { name: 'Hindi' },
  mr: { name: 'Marathi' },
  ta: { name: 'Tamil' },
  te: { name: 'Telugu' },
  en: { name: 'English' },
}

export function languageName(code: LanguageCode): string {
  return LANGUAGES[code].name
}
