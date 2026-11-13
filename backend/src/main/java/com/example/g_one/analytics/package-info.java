/**
 * Analytics: a record of every finished call. Operational data (times, duration, end reason, who joined, outcome,
 * latency, tool names and timings, languages, consent answers, private-advice kinds and times, the name-check flag)
 * and the deal card are kept for every call; the deal card is labelled "kept without consent: terms only" when no
 * party said yes. Only the farmer's yes keeps the caller key, the farmer's stated name and the memory snapshot; a call
 * without it is anonymous. Stored records never hold verbatim speech, translations, whisper or reply text, audio, tool
 * arguments or phone numbers.
 */
package com.example.g_one.analytics;
