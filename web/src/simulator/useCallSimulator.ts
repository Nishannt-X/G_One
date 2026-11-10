import { useCallback, useEffect, useRef, useState } from 'react'
import { backoffDelay } from '../lib/backoff'
import workletUrl from './capture.worklet.js?worker&url'
import { MicGate } from './micGate'
import { PcmPlayer } from './player'
import { PLAYBACK_RATE, parseServerMessage, socketUrl, type CallLine, type SocketParams } from './protocol'

export type CallStatus = 'idle' | 'connecting' | 'live' | 'ended' | 'error' | 'reconnecting'
export type MicMode = 'hands-free' | 'push-to-talk'

export interface SimulatorState {
  status: CallStatus
  /** Why the call ended or failed, in words for the person. */
  message: string
  callId: string | null
  /** True when the call is saved to and read from a farmer's memory. */
  memory: boolean
  muted: boolean
  micMode: MicMode
  talking: boolean
  textOnly: boolean
  assistantSpeaking: boolean
  framesSent: number
  framesReceived: number
  turnsSent: number
  micError: string | null
  /** The server turned this connection down, and `message` says why. Retrying the same call would not help. */
  refused: boolean
  /** A buyer's transcript. Stays empty for a farmer. */
  lines: CallLine[]
  /** This leg's language code, "auto" while it is being chosen, empty before the call connects. */
  language: string
}

const IDLE: SimulatorState = {
  status: 'idle',
  message: '',
  callId: null,
  memory: false,
  muted: false,
  micMode: 'hands-free',
  talking: false,
  textOnly: false,
  assistantSpeaking: false,
  framesSent: 0,
  framesReceived: 0,
  turnsSent: 0,
  micError: null,
  refused: false,
  lines: [],
  language: '',
}

const RETRY_CONFIG = { baseMs: 1000, maxMs: 8000, jitter: 0.2 }
const MAX_BUFFERED_BYTES = 8000 * 2
const MAX_LINES = 80
/** The server closes with these when it will not take the connection: policy violation, overload. */
const REFUSED_CODES = [1008, 1013]

/**
 * A context at the rate the backend streams at, so playback needs no resampling in our code, on larger output buffers
 * since a call favours steady audio over shaving milliseconds. Browsers that refuse the rate get their default one and
 * the player resamples instead.
 */
function createPlaybackContext(): AudioContext | null {
  try {
    return new AudioContext({ sampleRate: PLAYBACK_RATE, latencyHint: 'playback' })
  } catch {
    try {
      return new AudioContext({ latencyHint: 'playback' })
    } catch {
      return null
    }
  }
}

interface Session {
  socket: WebSocket
  context: AudioContext | null
  playbackContext: AudioContext | null
  stream: MediaStream | null
  player: PcmPlayer | null
  sent: number
  received: number
  turns: number
  muted: boolean
  micMode: MicMode
  talking: boolean
  textOnly: boolean
  teardown: () => void
}

export function useCallSimulator() {
  const [state, setState] = useState<SimulatorState>(IDLE)
  const session = useRef<Session | null>(null)
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const retryAttempt = useRef(0)
  const activeParams = useRef<(SocketParams & { textOnly?: boolean }) | null>(null)
  const userHungUp = useRef(false)
  const startRef = useRef<((params: SocketParams & { textOnly?: boolean }) => Promise<void>) | null>(null)
  const micModeRef = useRef<MicMode>(state.micMode)
  const statusRef = useRef<CallStatus>(state.status)

  const clearTimer = () => {
    if (reconnectTimer.current) {
      clearTimeout(reconnectTimer.current)
      reconnectTimer.current = null
    }
  }

  const finish = useCallback((status: 'ended' | 'error', message: string) => {
    clearTimer()
    session.current?.teardown()
    session.current = null
    setState((s) => ({ ...s, status, message, assistantSpeaking: false, talking: false }))
  }, [])

  const stop = useCallback(() => {
    userHungUp.current = true
    clearTimer()
    const s = session.current
    if (s && s.socket.readyState === WebSocket.OPEN) {
      try {
        s.socket.send(JSON.stringify({ type: 'hangup' }))
      } catch {
        // Socket closed
      }
    }
    finish('ended', 'You hung up.')
  }, [finish])

  const start = useCallback(
    async (params: SocketParams & { textOnly?: boolean }) => {
      userHungUp.current = false
      activeParams.current = params
      clearTimer()

      if (session.current) {
        session.current.teardown()
        session.current = null
      }

      setState((s) => ({
        ...IDLE,
        status: 'connecting',
        message: params.textOnly ? 'Connecting text session…' : 'Asking for the microphone…',
        textOnly: params.textOnly ?? false,
        micMode: s.micMode,
      }))

      let stream: MediaStream | null = null
      let context: AudioContext | null = null
      // Made here, inside the tap that started the call: once the microphone prompt has been answered, a phone browser
      // may no longer count this as a user gesture and would leave the context silent.
      const playbackContext = createPlaybackContext()
      void playbackContext?.resume()
      let capture: AudioWorkletNode | null = null

      if (!params.textOnly) {
        try {
          stream = await navigator.mediaDevices.getUserMedia({
            audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
          })
        } catch (error) {
          const denied =
            error instanceof DOMException &&
            (error.name === 'NotAllowedError' || error.name === 'SecurityError')
          const msg = denied
            ? 'The microphone is blocked. Allow it for this page in the browser, or switch to Text-Only mode below.'
            : 'No microphone was found or it could not be opened. You can continue in Text-Only mode below.'
          setState((s) => ({
            ...s,
            status: 'error',
            message: msg,
            micError: msg,
          }))
          void playbackContext?.close()
          return
        }
      }

      try {
        let player: PcmPlayer | null = null
        try {
          if (playbackContext) {
            void playbackContext.resume()
            player = new PcmPlayer(playbackContext, playbackContext.destination)
          }

          if (stream) {
            // The microphone keeps its own context at the browser's default rate.
            context = new AudioContext()
            await context.resume()
            await context.audioWorklet.addModule(workletUrl)
            capture = new AudioWorkletNode(context, 'pcm-capture', {
              numberOfInputs: 1,
              numberOfOutputs: 1,
              channelCount: 1,
            })
            const sink = context.createGain()
            sink.gain.value = 0
            capture.connect(sink).connect(context.destination)
            context.createMediaStreamSource(stream).connect(capture)
          }
        } catch {
          // If AudioContext fails in some environments, continue with audio muted
        }

        const socket = new WebSocket(socketUrl(params))
        socket.binaryType = 'arraybuffer'

        const live: Session = {
          socket,
          context,
          playbackContext,
          stream,
          player,
          sent: 0,
          received: 0,
          turns: 0,
          muted: false,
          micMode: micModeRef.current,
          talking: false,
          textOnly: Boolean(params.textOnly),
          teardown: () => {
            window.clearInterval(ticker)
            if (capture) capture.port.onmessage = null
            player?.clear()
            socket.onmessage = socket.onclose = socket.onerror = null
            if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
              socket.close()
            }
            stream?.getTracks().forEach((t) => t.stop())
            void context?.close()
            void playbackContext?.close()
          },
        }

        const ticker = window.setInterval(() => {
          setState((s) => ({
            ...s,
            framesSent: live.sent,
            framesReceived: live.received,
            turnsSent: live.turns,
            assistantSpeaking: player ? player.playing : false,
          }))
        }, 500)

        session.current = live
        setState((s) => ({ ...s, message: 'Connecting to the call…', micError: null }))

        const micGate = new MicGate()
        if (capture) {
          capture.port.onmessage = (event: MessageEvent<ArrayBuffer>) => {
            if (live.muted || socket.readyState !== WebSocket.OPEN || socket.bufferedAmount > MAX_BUFFERED_BYTES) {
              return
            }
            if (live.micMode === 'push-to-talk' && !live.talking) {
              return
            }
            // The microphone stays open while G-One speaks, so the person can talk over it (barge-in). In hands-free
            // mode the gate keeps leftover speaker echo out; push-to-talk is the person's own choice to speak.
            const playing = live.micMode === 'hands-free' && player !== null && player.playing
            const { send, opened } = micGate.push(event.data, playing)
            if (opened) console.debug('[g-one] speech over playback: microphone sent for barge-in')
            for (const frame of send) {
              socket.send(frame)
              live.sent++
            }
          }
        }

        socket.onmessage = (event: MessageEvent<string | ArrayBuffer>) => {
          if (typeof event.data !== 'string') {
            live.received++
            player?.enqueue(event.data)
            return
          }
          const message = parseServerMessage(event.data)
          if (!message) return
          switch (message.type) {
            case 'ready':
              retryAttempt.current = 0
              player?.setWireRate(message.sampleRate)
              setState((s) => ({
                ...s,
                status: 'live',
                message: 'On the call.',
                callId: message.callId,
                memory: message.memory,
                language: message.language,
              }))
              break
            case 'language':
              setState((s) => ({ ...s, language: message.language }))
              break
            case 'mark':
              player?.whenPlayed(() => {
                if (socket.readyState === WebSocket.OPEN) {
                  socket.send(JSON.stringify({ type: 'mark', id: message.id }))
                }
              })
              break
            case 'clear':
              player?.clear()
              console.debug('[g-one] clear from the server: playback flushed')
              break
            case 'end':
              finish('ended', 'The call ended.')
              break
            case 'line':
              setState((s) => ({ ...s, lines: [...s.lines, { kind: message.kind, language: message.language, text: message.text }].slice(-MAX_LINES) }))
              break
          }
        }

        socket.onclose = (event) => {
          if (userHungUp.current || event.code === 1000) {
            finish('ended', 'The call ended.')
            return
          }
          if (REFUSED_CODES.includes(event.code)) {
            finish('error', event.reason || 'The call would not accept this connection.')
            setState((s) => ({ ...s, refused: true }))
            return
          }
          // Unexpected drop: attempt reconnect with backoff
          if (activeParams.current) {
            retryAttempt.current++
            const delay = backoffDelay(retryAttempt.current, RETRY_CONFIG)
            setState((s) => ({
              ...s,
              status: 'reconnecting',
              message: `Connection lost. Reconnecting in ${(delay / 1000).toFixed(1)}s (attempt ${retryAttempt.current})…`,
            }))
            live.teardown()
            session.current = null
            reconnectTimer.current = setTimeout(() => {
              if (activeParams.current && !userHungUp.current) {
                void startRef.current?.(activeParams.current)
              }
            }, delay)
          } else {
            finish('error', 'The connection to the backend was lost.')
          }
        }

        socket.onerror = () => {
          if (statusRef.current === 'connecting') {
            finish('error', 'Could not reach the backend. Is it running?')
          }
        }
      } catch (error) {
        if (session.current) session.current.teardown()
        else {
          stream?.getTracks().forEach((t) => t.stop())
          void context?.close()
          void playbackContext?.close()
        }
        session.current = null
        setState({
          ...IDLE,
          status: 'error',
          message:
            error instanceof Error && error.name === 'NotSupportedError'
              ? 'This browser cannot capture audio here.'
              : 'The audio could not be started in this browser.',
        })
      }
    },
    [finish],
  )

  useEffect(() => {
    micModeRef.current = state.micMode
    statusRef.current = state.status
    startRef.current = start
  }, [state.micMode, state.status, start])

  const say = useCallback((text: string) => {
    const s = session.current
    if (!s || s.socket.readyState !== WebSocket.OPEN || !text.trim()) return
    s.socket.send(JSON.stringify({ type: 'say', text: text.trim() }))
    s.turns++
    setState((prev) => ({ ...prev, turnsSent: s.turns }))
  }, [])

  const setMuted = useCallback((muted: boolean) => {
    if (session.current) session.current.muted = muted
    setState((s) => ({ ...s, muted }))
  }, [])

  const setMicMode = useCallback((micMode: MicMode) => {
    if (session.current) session.current.micMode = micMode
    setState((s) => ({ ...s, micMode }))
  }, [])

  const setTalking = useCallback((talking: boolean) => {
    if (session.current) session.current.talking = talking
    setState((s) => ({ ...s, talking }))
  }, [])

  const retry = useCallback(() => {
    if (activeParams.current) {
      void start(activeParams.current)
    }
  }, [start])

  useEffect(
    () => () => {
      userHungUp.current = true
      clearTimer()
      session.current?.teardown()
      session.current = null
    },
    [],
  )

  return { state, start, stop, say, setMuted, setMicMode, setTalking, retry }
}
