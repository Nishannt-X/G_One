export type UiLang = 'en' | 'hi'

const LANGUAGES: Record<string, { en: string; hi: string }> = {
  'hi-IN': { en: 'Hindi', hi: 'हिंदी' },
  'en-IN': { en: 'English', hi: 'अंग्रेज़ी' },
  'hi-en': { en: 'Hinglish', hi: 'हिंग्लिश' },
  'bn-IN': { en: 'Bengali', hi: 'बांग्ला' },
  'bn-en': { en: 'Benglish', hi: 'बेंग्लिश' },
  'ta-IN': { en: 'Tamil', hi: 'तमिल' },
  'te-IN': { en: 'Telugu', hi: 'तेलुगु' },
  'kn-IN': { en: 'Kannada', hi: 'कन्नड़' },
  'ml-IN': { en: 'Malayalam', hi: 'मलयालम' },
  'mr-IN': { en: 'Marathi', hi: 'मराठी' },
  'gu-IN': { en: 'Gujarati', hi: 'गुजराती' },
  'pa-IN': { en: 'Punjabi', hi: 'पंजाबी' },
}

/** "hi-IN" becomes "Hindi"; an unknown code is shown as it is. */
export function languageLabel(code: string, ui: UiLang = 'en'): string {
  return LANGUAGES[code]?.[ui] ?? code
}

/** A length of time as m:ss, or h:mm:ss from one hour. */
export function duration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const s = String(total % 60).padStart(2, '0')
  const m = Math.floor(total / 60) % 60
  const h = Math.floor(total / 3600)
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}

/** How far into the call something happened, such as "+0:12". */
export function offset(atMs: number, startMs: number): string {
  return `+${duration(atMs - startMs)}`
}

/** A measured time: "840 ms" below a second, "1.4 s" from one second. */
export function millis(ms: number): string {
  const value = Math.max(0, Math.round(ms))
  return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`
}

const RUPEES = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2, minimumFractionDigits: 0 })

/** 215000 becomes "₹2,15,000". */
export function rupees(amount: number): string {
  return RUPEES.format(amount)
}

const DATES = {
  en: new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short' }),
  hi: new Intl.DateTimeFormat('hi-IN', { day: 'numeric', month: 'short' }),
}

export function shortDate(ms: number, ui: UiLang = 'en'): string {
  return DATES[ui].format(new Date(ms))
}

/** "usual_buyer_type" and "usualMandi" become "usual buyer type" and "usual mandi". */
export function humanize(field: string): string {
  return field.replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()
}

export function shortId(id: string, keep = 14): string {
  return id.length > keep ? `${id.slice(0, keep)}…` : id
}
