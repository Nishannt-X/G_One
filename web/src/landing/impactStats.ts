/**
 * The problem in numbers: published context about Indian farming, phones and fraud, each with its year, population and
 * source. These describe the problem, not anything G-One has achieved. Researched 10 Nov 2026 from Government of India
 * releases on PIB; keep a figure only with its source, and never add a figure without one.
 */
export interface ImpactStat {
  id: string
  /** The figure as printed, at the source's precision. A ' / ' pairs two figures, matched in order by the label. */
  figure: string
  /** One line: what was counted, for whom, and when. */
  label: string
  /** The caveat a reader needs so the figure is not over-read. */
  note: string
  source: { name: string; url: string }
}

export const IMPACT_FOOTNOTE = 'Context about the problem, not results achieved by G-One. Survey figures are estimates.'

export const IMPACT_STATS: readonly ImpactStat[] = [
  {
    id: 'households',
    figure: '9.30 crore',
    label: 'rural agricultural households in India, 2019 survey estimate',
    note: 'Households, not individual farmers.',
    source: { name: 'Ministry of Agriculture via PIB', url: 'https://www.pib.gov.in/PressReleasePage.aspx?PRID=2298040&lang=1&reg=3' },
  },
  {
    id: 'smallholdings',
    figure: '89.4%',
    label: 'of rural agricultural households owned less than two hectares, 2019 survey',
    note: 'Land owned, for the 2018–19 agricultural year.',
    source: { name: 'Ministry of Agriculture and NSO via PIB', url: 'https://www.pib.gov.in/PressReleasePage.aspx?PRID=1910357&lang=2&reg=48' },
  },
  {
    id: 'youth-mobile',
    figure: '96.8%',
    label: 'of rural people aged 15–29 used a mobile phone in the previous three months, Jan–Mar 2025',
    note: 'Use for calls or internet, not ownership.',
    source: { name: 'MoSPI telecom survey via PIB', url: 'https://www.pib.gov.in/Pressreleaseshare.aspx?PRID=2132330&lang=2&reg=3' },
  },
  {
    id: 'ownership-gap',
    figure: '80.7% / 48.4%',
    label: 'of rural men / rural women aged 15 and over owned a mobile phone, Jan–Mar 2025',
    note: 'A phone in the household is not a phone of one’s own.',
    source: { name: 'MoSPI telecom survey via PIB', url: 'https://www.pib.gov.in/Pressreleaseshare.aspx?PRID=2132330&lang=2&reg=3' },
  },
  {
    id: 'cyber-complaints',
    figure: '8.2 million+',
    label: 'cybercrime-related complaints registered across India, Jan 2020 to Nov 2025',
    note: 'National and of every kind, not farmer-specific. Complaints, not proven crimes.',
    source: { name: 'Ministry of Home Affairs via PIB', url: 'https://www.pib.gov.in/PressReleasePage.aspx?PRID=2226082&lang=1&reg=6' },
  },
  {
    id: 'languages',
    figure: '~972 / ~690 lakh',
    label: 'people recorded as Bengali / Tamil speakers in Census 2011, approximate',
    note: 'Census-era counts, not today’s population or G-One’s reach.',
    source: { name: 'Ministry of Culture via PIB', url: 'https://www.pib.gov.in/PressReleasePage.aspx?PRID=2222171&lang=2&reg=3' },
  },
]
