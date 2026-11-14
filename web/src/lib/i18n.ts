import { useCallback, useSyncExternalStore } from 'react'
import type { UiLang } from './format'

export type MessageKey =
  | 'farmer'
  | 'buyer'
  | 'assistant'
  | 'whisper'
  | 'on_the_line'
  | 'call_ended'
  | 'call_stored'
  | 'call_started_at'
  | 'leg_not_stored'
  | 'whisper_stored_note'
  | 'whisper_stored_empty'
  | 'memory_stored_note'
  | 'memory_not_this_farmer'
  | 'memory_not_stored'
  | 'tools_result_not_kept'
  | 'kept_with_yes'
  | 'kept_none'
  | 'deal_terms_only'
  | 'latency_stored_median'
  | 'latency_stored_note'
  | 'latency_stored_approximate'
  | 'ended'
  | 'calls'
  | 'deal_title'
  | 'deal_receipt_button'
  | 'deal_agreed'
  | 'deal_closed'
  | 'deal_progress'
  | 'deal_nonbinding'
  | 'deal_step_heard'
  | 'deal_step_readback'
  | 'deal_step_yes'
  | 'deal_step_agreed'
  | 'said_yes'
  | 'not_yet'
  | 'term_crop'
  | 'term_quantity'
  | 'term_price'
  | 'term_value'
  | 'term_market'
  | 'term_pickup'
  | 'activity_assistant'
  | 'activity_whisper'
  | 'activity_person'
  | 'activity_idle'
  | 'leg_waiting'
  | 'leg_connected'
  | 'leg_not_joined'
  | 'leg_ended'
  | 'source_twilio'
  | 'source_simulator'
  | 'phase_ended'
  | 'phase_listening'
  | 'phase_translating'
  | 'phase_whisper'
  | 'phase_speaking'
  | 'phase_tool'
  | 'whisper_marker'
  | 'whisper_tip'
  | 'whisper_caution'
  | 'whisper_deal'
  | 'whisper_price_reference'
  | 'assistant_to'
  | 'translated_for'
  | 'translating'
  | 'subtitles'
  | 'subtitles_hint'
  | 'subtitles_empty'
  | 'subtitles_empty_body'
  | 'jump_latest'
  | 'whisper_title'
  | 'whisper_note'
  | 'whisper_empty'
  | 'farmer_only'
  | 'latency_title'
  | 'latency_chart'
  | 'latency_transcript'
  | 'latency_transcript_hint'
  | 'latency_translation'
  | 'latency_translation_hint'
  | 'latency_reply'
  | 'latency_reply_hint'
  | 'latency_median'
  | 'latency_empty'
  | 'latency_scripted'
  | 'latency_measured'
  | 'latency_scripted_note'
  | 'latency_note'
  | 'memory_glance'
  | 'memory_returning'
  | 'memory_first'
  | 'memory_none_loaded'
  | 'memory_nothing_yet'
  | 'memory_last_call'
  | 'memory_open'
  | 'memory_open_items'
  | 'memory_open_none'
  | 'meter_title'
  | 'meter_above'
  | 'meter_below'
  | 'meter_same'
  | 'meter_offer'
  | 'meter_dated'
  | 'meter_waiting'
  | 'meter_waiting_offer'
  | 'meter_reference'
  | 'meter_snapshot'
  | 'meter_no_date'
  | 'tools_title'
  | 'tools_empty'
  | 'tools_result'
  | 'sample_failed'
  | 'backend_unreachable'
  | 'backend_unreachable_fix'
  | 'prov_said'
  | 'prov_inferred'
  | 'banner_mock'
  | 'banner_sample'
  | 'waiting_title'
  | 'waiting_body'
  | 'waiting_offline'
  | 'waiting_offline_body'
  | 'open_simulator'
  | 'retry'
  | 'reason_deal_agreed'
  | 'reason_buyer_declined'
  | 'reason_farmer_left'
  | 'reason_call_ended'
  | 'nav_home'
  | 'nav_live'
  | 'nav_buyers'
  | 'nav_memory'
  | 'nav_simulator'
  | 'theme_dark'
  | 'theme_light'
  | 'lang_toggle'
  | 'buyers_title'
  | 'buyers_search'
  | 'buyers_crop'
  | 'buyers_district'
  | 'buyers_sort'
  | 'buyers_empty'
  | 'memory_title'
  | 'memory_search'
  | 'memory_forget'
  | 'memory_forget_confirm'
  | 'memory_forgotten'
  | 'memory_forget_completing'
  | 'memory_empty'
  | 'credit_speech_live'
  | 'credit_speech_sample'

const MESSAGES: Record<UiLang, Record<MessageKey, string>> = {
  en: {
    farmer: 'Farmer',
    buyer: 'Buyer',
    assistant: 'G-One Assistant',
    whisper: 'Private Whisper',
    on_the_line: 'Live Call',
    call_ended: 'Call Ended',
    call_stored: 'Stored call',
    call_started_at: 'Started {time}',
    leg_not_stored: 'Not stored',
    whisper_stored_note: 'Only the kind and time of each note were kept, never the words.',
    whisper_stored_empty: 'No private advice was given on this call.',
    memory_stored_note: 'What G-One remembered when this call began.',
    memory_not_this_farmer: 'The caller said they were not the remembered farmer, so that memory is not attached to this call.',
    memory_not_stored: 'Not stored: the farmer did not say yes to remembering this call.',
    tools_result_not_kept: 'Not kept: only the tool, its time and how long it took are stored.',
    kept_with_yes: 'Kept with a yes: {parties}',
    kept_none: 'Conversation not kept: no one said yes',
    deal_terms_only: 'Kept without consent: terms only',
    latency_stored_median: 'Call median',
    latency_stored_note: 'Per-turn times are kept only with a yes; these are the medians kept for every call.',
    latency_stored_approximate: 'Where both sides were measured, the median is approximate.',
    ended: 'Ended',
    calls: 'Calls',
    deal_title: 'Deal Card',
    deal_receipt_button: 'View Sauda Parchi (Receipt) ↗',
    credit_speech_live: 'Speech: Gnani Prisma (recognition) · Timbre (voice)',
    credit_speech_sample: 'Voices: Gnani Timbre (recorded sample)',
    deal_agreed: 'Deal Agreed',
    deal_closed: 'Deal Closed',
    deal_progress: 'Deal Progress',
    deal_nonbinding: 'Indicative terms heard on call. Nonbinding until pickup.',
    deal_step_heard: 'Terms Heard',
    deal_step_readback: 'Readback',
    deal_step_yes: 'Confirmation',
    deal_step_agreed: 'Agreed',
    said_yes: 'Agreed',
    not_yet: 'Pending',
    term_crop: 'Crop',
    term_quantity: 'Quantity',
    term_price: 'Price',
    term_value: 'Estimated Value',
    term_market: 'Market / Mandi',
    term_pickup: 'Pickup & Payment',
    activity_assistant: 'Assistant speaking',
    activity_whisper: 'Private whisper',
    activity_person: 'Speaking',
    activity_idle: 'Silent',
    leg_waiting: 'Waiting to join',
    leg_connected: 'Connected',
    leg_not_joined: 'Not joined',
    leg_ended: 'Ended ({reason})',
    source_twilio: 'Phone',
    source_simulator: 'Simulator',
    phase_ended: 'Call ended',
    phase_listening: 'Listening to both sides',
    phase_translating: 'Translating for {role}',
    phase_whisper: 'Whispering advice to farmer',
    phase_speaking: 'Speaking to {role}',
    phase_tool: 'Checking {tool}',
    whisper_marker: 'Whisper ({kind})',
    whisper_tip: 'Market Tip',
    whisper_caution: 'Caution Alert',
    whisper_deal: 'Deal Term',
    whisper_price_reference: 'Indicative Price Sample',
    assistant_to: 'Assistant to {role}',
    translated_for: 'Translated for {role} ({language})',
    translating: 'Translating…',
    subtitles: 'Live Subtitles',
    subtitles_hint: 'Farmer (left) · Buyer (right)',
    subtitles_empty: 'No speech yet',
    subtitles_empty_body: 'Spoken dialogue will appear here with translations.',
    jump_latest: 'Jump to latest',
    whisper_title: 'Private Whisper Advice',
    whisper_note: 'Only the farmer hears these private advisory notes.',
    whisper_empty: 'No whisper advice given yet.',
    farmer_only: 'Farmer Only',
    latency_title: 'Turn Latency (Measured)',
    latency_chart: 'Latency breakdown',
    latency_transcript: 'Speech to Text',
    latency_transcript_hint: 'Audio end to transcript received',
    latency_translation: 'Translation',
    latency_translation_hint: 'Transcript to translated speech',
    latency_reply: 'Assistant Turn',
    latency_reply_hint: 'Question to spoken answer',
    latency_median: 'Median',
    latency_empty: 'Awaiting turn timings',
    latency_scripted: 'Indicative Sample',
    latency_measured: 'Measured',
    latency_scripted_note: 'Timings from scripted simulation run.',
    latency_note: 'Per-turn timings measured on server clock.',
    memory_glance: 'Farmer Memory',
    memory_returning: 'Returning Caller',
    memory_first: 'First-time Caller',
    memory_none_loaded: 'No memory loaded for this call.',
    memory_nothing_yet: 'No prior facts recorded for this caller.',
    memory_last_call: 'Last call: {date}',
    memory_open: 'View memory profile →',
    memory_open_items: 'Open follow-ups',
    memory_open_none: 'Nothing open',
    meter_title: 'Price Comparison',
    meter_above: '{pct}% above indicative reference',
    meter_below: '{pct}% below indicative reference',
    meter_same: 'Matches indicative reference',
    meter_offer: 'Offer: {offer} · Reference: {reference}',
    meter_dated: 'Source: {source} ({date})',
    meter_waiting: 'Awaiting price term',
    meter_waiting_offer: 'Waiting for agreed price term',
    meter_reference: 'Indicative Sample',
    meter_snapshot: 'Sample modal price',
    meter_no_date: 'dated sample',
    tools_title: 'AI Tools & Lookups',
    tools_empty: 'No tools called yet.',
    tools_result: 'Result',
    sample_failed: 'Failed to start demo',
    backend_unreachable: 'Backend Unreachable',
    backend_unreachable_fix: 'Ensure Spring Boot backend is running on port 8080.',
    prov_said: 'Spoken by farmer',
    prov_inferred: 'Inferred from context',
    banner_mock: 'Mock call generated locally for testing.',
    banner_sample: 'Synthetic demo call running through backend pipeline.',
    waiting_title: 'Waiting for a Call',
    waiting_body: 'When a call connects, live translation and deal terms will appear here.',
    waiting_offline: 'Console Offline',
    waiting_offline_body: 'Connecting to the backend console websocket…',
    open_simulator: 'Open Call Simulator',
    retry: 'Retry',
    reason_deal_agreed: 'Deal Agreed: Both farmer and buyer confirmed all terms.',
    reason_buyer_declined: 'Buyer Declined: The buyer declined the terms and hung up.',
    reason_farmer_left: 'Farmer Left: The farmer ended the call before agreement.',
    reason_call_ended: 'Call Ended: {reason}',
    nav_home: 'Home & Studio',
    nav_live: 'Live Call',
    nav_buyers: 'Buyer Directory',
    nav_memory: 'Farmer Memory',
    nav_simulator: 'Simulator',
    theme_dark: 'Dark mode',
    theme_light: 'Light mode',
    lang_toggle: 'हिंदी',
    buyers_title: 'Synthetic Buyer Directory',
    buyers_search: 'Search by buyer, crop, or district',
    buyers_crop: 'All Crops',
    buyers_district: 'All Districts',
    buyers_sort: 'Sort by',
    buyers_empty: 'No buyers match the filter criteria.',
    memory_title: 'Farmer Memory Profile',
    memory_search: 'Enter caller key (64 hex characters)',
    memory_forget: 'Forget Caller Profile',
    memory_forget_confirm: 'Are you sure? This permanently deletes stored facts and call gists for this caller.',
    memory_forgotten: 'Caller profile has been forgotten.',
    memory_forget_completing: 'Forget accepted, completing: the call records are unlinked, and the profile is removed when G-One retries.',
    memory_empty: 'No profile facts found for this caller.',
  },
  hi: {
    farmer: 'किसान',
    buyer: 'व्यापारी',
    assistant: 'जी-वन सहायक',
    whisper: 'निजी सलाह',
    on_the_line: 'कॉल चालू है',
    call_ended: 'कॉल समाप्त',
    call_stored: 'सहेजी गई कॉल',
    call_started_at: 'शुरू {time}',
    leg_not_stored: 'सहेजा नहीं गया',
    whisper_stored_note: 'हर सलाह का केवल प्रकार और समय रखा गया, शब्द कभी नहीं।',
    whisper_stored_empty: 'इस कॉल में कोई निजी सलाह नहीं दी गई।',
    memory_stored_note: 'कॉल शुरू होने पर G-One को जो याद था।',
    memory_not_this_farmer: 'कॉल करने वाले ने कहा कि वे याद किए गए किसान नहीं हैं, इसलिए वह जानकारी इस कॉल से नहीं जोड़ी गई।',
    memory_not_stored: 'सहेजा नहीं गया: किसान ने इस कॉल को याद रखने के लिए हाँ नहीं कहा।',
    tools_result_not_kept: 'नहीं रखा गया: केवल टूल, उसका समय और लगा समय सहेजा जाता है।',
    kept_with_yes: 'हाँ कहने पर रखा गया: {parties}',
    kept_none: 'बातचीत नहीं रखी गई: किसी ने हाँ नहीं कहा',
    deal_terms_only: 'बिना सहमति के रखा गया: केवल शर्तें',
    latency_stored_median: 'कॉल का मीडियन',
    latency_stored_note: 'हर टर्न का समय केवल हाँ कहने पर रखा जाता है; ये हर कॉल के लिए रखे गए मीडियन हैं।',
    latency_stored_approximate: 'जहाँ दोनों तरफ़ माप हुआ, वहाँ मीडियन अनुमानित है।',
    ended: 'समाप्त',
    calls: 'कॉल्स',
    deal_title: 'सौदा पर्ची',
    deal_receipt_button: 'सौदा पर्ची देखें (Receipt) ↗',
    credit_speech_live: 'वाणी: Gnani Prisma (पहचान) · Timbre (आवाज़)',
    credit_speech_sample: 'आवाज़ें: Gnani Timbre (रिकॉर्ड किया गया सैंपल)',
    deal_agreed: 'सौदा पक्का',
    deal_closed: 'सौदा रद्द',
    deal_progress: 'सौदा प्रगति',
    deal_nonbinding: 'कॉल पर तय शर्तें। माल उठने तक गैर-बाध्यकारी।',
    deal_step_heard: 'शर्तें सुनीं',
    deal_step_readback: 'दोहराया',
    deal_step_yes: 'पुष्टि',
    deal_step_agreed: 'पक्का',
    said_yes: 'सहमति दी',
    not_yet: 'प्रतीक्षारत',
    term_crop: 'फसल',
    term_quantity: 'मात्रा',
    term_price: 'भाव',
    term_value: 'कुल अनुमानित मूल्य',
    term_market: 'मंडी / बाज़ार',
    term_pickup: 'उठान व भुगतान',
    activity_assistant: 'सहायक बोल रहा है',
    activity_whisper: 'निजी सलाह',
    activity_person: 'बोल रहा है',
    activity_idle: 'शांत',
    leg_waiting: 'जुड़ने की प्रतीक्षा',
    leg_connected: 'जुड़े हुए हैं',
    leg_not_joined: 'शामिल नहीं',
    leg_ended: 'हटे ({reason})',
    source_twilio: 'फ़ोन',
    source_simulator: 'सिमुलेटर',
    phase_ended: 'कॉल समाप्त',
    phase_listening: 'दोनों पक्षों को सुन रहे हैं',
    phase_translating: '{role} के लिए अनुवाद',
    phase_whisper: 'किसान को निजी सलाह',
    phase_speaking: '{role} से बात',
    phase_tool: '{tool} जांच रहे हैं',
    whisper_marker: 'सलाह ({kind})',
    whisper_tip: 'बाज़ार सलाह',
    whisper_caution: 'सावधानी चेतावनी',
    whisper_deal: 'सौदा शर्त',
    whisper_price_reference: 'नमूना भाव संदर्भ',
    assistant_to: '{role} से सहायक',
    translated_for: '{role} के लिए अनुवाद ({language})',
    translating: 'अनुवाद हो रहा है…',
    subtitles: 'लाइव सबटाइटल',
    subtitles_hint: 'किसान (बाएं) · व्यापारी (दाएं)',
    subtitles_empty: 'अभी कोई बातचीत नहीं',
    subtitles_empty_body: 'बोली गई बातें और उनका अनुवाद यहां दिखाई देगा।',
    jump_latest: 'नवीनतम पर जाएं',
    whisper_title: 'किसान निजी सलाह',
    whisper_note: 'यह निजी सलाह केवल किसान को सुनाई देती है, व्यापारी को नहीं।',
    whisper_empty: 'अभी तक कोई निजी सलाह नहीं दी गई।',
    farmer_only: 'केवल किसान',
    latency_title: 'मापा गया विलंब (Latency)',
    latency_chart: 'विलंब चार्ट',
    latency_transcript: 'ध्वनि से पाठ',
    latency_transcript_hint: 'आवाज़ समाप्त होने से पाठ मिलने तक का समय',
    latency_translation: 'अनुवाद',
    latency_translation_hint: 'पाठ से अनुवादित आवाज़ तक का समय',
    latency_reply: 'सहायक उत्तर',
    latency_reply_hint: 'प्रश्न से उत्तर तक का समय',
    latency_median: 'औसत (मीडियन)',
    latency_empty: 'माप की प्रतीक्षा है',
    latency_scripted: 'नमूना डेटा',
    latency_measured: 'मापा गया',
    latency_scripted_note: 'स्क्रिप्टेड सिमुलेशन से लिया गया समय।',
    latency_note: 'सर्वर घड़ी पर मापा गया प्रति-टर्न समय।',
    memory_glance: 'किसान प्रोफाइल स्मृति',
    memory_returning: 'पुराने किसान',
    memory_first: 'पहली कॉल',
    memory_none_loaded: 'इस कॉल के लिए कोई प्रोफाइल लोड नहीं हुई।',
    memory_nothing_yet: 'इस कॉलर के लिए पूर्व जानकारी दर्ज नहीं है।',
    memory_last_call: 'पिछली बातचीत: {date}',
    memory_open: 'प्रोफाइल विवरण देखें →',
    memory_open_items: 'बाकी काम',
    memory_open_none: 'कुछ बाकी नहीं',
    meter_title: 'भाव तुलना',
    meter_above: 'संदर्भ भाव से {pct}% अधिक',
    meter_below: 'संदर्भ भाव से {pct}% कम',
    meter_same: 'संदर्भ भाव के बराबर',
    meter_offer: 'प्रस्ताव: {offer} · संदर्भ: {reference}',
    meter_dated: 'स्रोत: {source} ({date})',
    meter_waiting: 'भाव की प्रतीक्षा है',
    meter_waiting_offer: 'सहमति वाले भाव की प्रतीक्षा है',
    meter_reference: 'नमूना संदर्भ',
    meter_snapshot: 'मंडी मॉडल भाव',
    meter_no_date: 'दिनांकित नमूना',
    tools_title: 'उपकरण व जांच',
    tools_empty: 'अभी तक कोई उपकरण उपयोग नहीं हुआ।',
    tools_result: 'परिणाम',
    sample_failed: 'डेमो शुरू करने में विफल',
    backend_unreachable: 'बैकएंड अनुपलब्ध',
    backend_unreachable_fix: 'जांचें कि पोर्ट 8080 पर बैकएंड चल रहा है।',
    prov_said: 'किसान ने कहा',
    prov_inferred: 'बातचीत से समझा',
    banner_mock: 'लोकल टेस्ट के लिए बनाया गया मॉक कॉल।',
    banner_sample: 'बैकएंड पाइपलाइन से चल रहा डेमो कॉल।',
    waiting_title: 'कॉल की प्रतीक्षा है',
    waiting_body: 'कॉल जुड़ते ही लाइव अनुवाद और सौदे की शर्तें यहां दिखाई देंगी।',
    waiting_offline: 'कंसोल ऑफ़लाइन',
    waiting_offline_body: 'बैकएंड कंसोल से जुड़ने का प्रयास जारी है…',
    open_simulator: 'कॉल सिमुलेटर खोलें',
    retry: 'पुनः प्रयास करें',
    reason_deal_agreed: 'सौदा पक्का: किसान और व्यापारी दोनों ने सभी शर्तों पर सहमति दी।',
    reason_buyer_declined: 'व्यापारी अस्वीकृत: व्यापारी ने शर्तें स्वीकार नहीं कीं और कॉल समाप्त की।',
    reason_farmer_left: 'किसान हटे: किसान ने बातचीत से पहले कॉल समाप्त की।',
    reason_call_ended: 'कॉल समाप्त: {reason}',
    nav_home: 'होम एवं स्टूडियो',
    nav_live: 'लाइव कॉल',
    nav_buyers: 'व्यापारी निर्देशिका',
    nav_memory: 'किसान स्मृति',
    nav_simulator: 'सिमुलेटर',
    theme_dark: 'डार्क मोड',
    theme_light: 'लाइट मोड',
    lang_toggle: 'English',
    buyers_title: 'व्यापारी निर्देशिका (सिंथेटिक)',
    buyers_search: 'व्यापारी, फसल, या ज़िला खोजें',
    buyers_crop: 'सभी फसलें',
    buyers_district: 'सभी ज़िले',
    buyers_sort: 'क्रमबद्ध करें',
    buyers_empty: 'खोज के अनुसार कोई व्यापारी नहीं मिला।',
    memory_title: 'किसान प्रोफाइल स्मृति',
    memory_search: 'कॉलर की (64 हेक्स वर्ण) दर्ज करें',
    memory_forget: 'प्रोफाइल हटाएं (Forget)',
    memory_forget_confirm: 'क्या आप सुनिश्चित हैं? इससे इस कॉलर के सभी दर्ज तथ्य और सारांश हटा दिए जाएंगे।',
    memory_forgotten: 'कॉलर का रिकॉर्ड हटा दिया गया है।',
    memory_forget_completing: 'हटाने का अनुरोध स्वीकार, पूरा हो रहा है: कॉल रिकॉर्ड अलग कर दिए गए, प्रोफाइल दोबारा कोशिश पर हटेगी।',
    memory_empty: 'इस कॉलर का कोई विवरण नहीं मिला।',
  },
}

const I18N_KEY = 'g-one-ui-lang'

let currentLang: UiLang = (function () {
  try {
    const saved = localStorage.getItem(I18N_KEY)
    return saved === 'hi' ? 'hi' : 'en'
  } catch {
    return 'en'
  }
})()

const listeners = new Set<() => void>()

function subscribe(onChange: () => void) {
  listeners.add(onChange)
  return () => {
    listeners.delete(onChange)
  }
}

function setLanguage(next: UiLang) {
  currentLang = next
  try {
    localStorage.setItem(I18N_KEY, next)
  } catch {
    // Storage blocked: fallback to in-memory
  }
  listeners.forEach((l) => l())
}

export function useI18n() {
  const lang = useSyncExternalStore(subscribe, () => currentLang, () => 'en' as UiLang)

  const setLang = useCallback((next: UiLang) => {
    setLanguage(next)
  }, [])

  const t = useCallback(
    (key: MessageKey, params?: Record<string, string | number>): string => {
      const raw = MESSAGES[lang]?.[key] ?? MESSAGES.en[key] ?? key
      if (!params) return raw
      return Object.entries(params).reduce(
        (acc, [k, v]) => acc.replace(new RegExp(`\\{${k}\\}`, 'g'), String(v)),
        raw,
      )
    },
    [lang],
  )

  return { lang, setLang, t }
}

export function useT() {
  return useI18n().t
}
