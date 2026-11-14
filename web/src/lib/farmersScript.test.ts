import { describe, expect, test } from 'bun:test'
import { englishName, englishText, hasNonLatinLetters, initialOf } from './farmersScript'

// Invented names only. The same cases as backend EnglishLettersTest, so the two tables stay in step.
const CASES: [string, string][] = [
  ['रमेश पाटिल', 'Ramesh Patil'],
  ['रामेश्वर पाटिल', 'Rameshwar Patil'],
  ['कमला देवी', 'Kamla Devi'],
  ['गुरप्रीत सिंह', 'Gurprit Singh'],
  ['राजेन्द्र', 'Rajendra'],
  ['अमरनाथ', 'Amarnath'],
  ['लक्ष्मी', 'Lakshmi'],
  ['चंपा', 'Champa'],
  ['हरि', 'Hari'],
  ['মিতা দাস', 'Mita Das'],
  ['সুমন রায়', 'Suman Ray'],
  ['মৃণাল', 'Mrinal'],
  ['செல்வி', 'Selvi'],
  ['சரவணன்', 'Saravanan'],
  ['முருகன்', 'Murukan'],
  ['ರಮೇಶ್', 'Ramesh'],
  ['ಲಕ್ಷ್ಮಿ', 'Lakshmi'],
  ['ಸುಮಾ', 'Suma'],
]

describe('English letters', () => {
  test.each(CASES)('%s reads as %s', (native, english) => {
    expect(englishName(native)).toBe(english)
    expect(hasNonLatinLetters(englishName(native) ?? '')).toBe(false)
  })

  test('mixed and odd input', () => {
    expect(englishName('Ramesh पाटिल')).toBe('Ramesh Patil')
    expect(englishName('  Asha  ')).toBe('Asha')
    expect(englishName('Ramēsh')).toBe('Ramesh')
    expect(englishName('राज123')).toBe('Raj')
    expect(englishName('रमेश‍पाटिल')).toMatch(/^[A-Za-z ]+$/)
    expect(englishName("O'Neil-Rao.")).toBe("O'Neil-Rao")
    for (const nothing of ['王伟', 'محمد', '123', '  ', '🙂', '-', null, undefined]) expect(englishName(nothing)).toBeNull()
  })

  test('other values keep digits and drop letters it cannot read', () => {
    expect(englishText('40 क्विंटल')).toBe('40 Kwintal')
    expect(englishText('Lasalgaon APMC')).toBe('Lasalgaon APMC')
    expect(englishText('नासिक')).toBe('Nasik')
    expect(englishText('王伟')).toBeNull()
  })

  test('the avatar initial is the first English letter', () => {
    expect(initialOf('रमेश पाटिल')).toBe('R')
    expect(initialOf('মিতা দাস')).toBe('M')
    expect(initialOf('  asha')).toBe('A')
    expect(initialOf('王伟')).toBeNull()
    expect(initialOf(null)).toBeNull()
  })
})
