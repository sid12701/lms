import type { ReactNode } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { cn } from '@/lib/utils'

type BlueLoaderProps = {
  title?: string
  description?: ReactNode
  className?: string
  compact?: boolean
}

const easeOutQuint = [0.22, 1, 0.36, 1] as const
const sweepEase = [0.65, 0, 0.35, 1] as const

export function BlueLoader({
  title = 'Loading workspace',
  description,
  className,
  compact = false,
}: BlueLoaderProps) {
  const prefersReducedMotion = useReducedMotion()
  const entranceInitial = prefersReducedMotion ? false : { opacity: 0, y: 8, scale: 0.995 }
  const entranceAnimate = { opacity: 1, y: 0, scale: 1 }
  const sweepAnimate = prefersReducedMotion ? { x: '0%' } : { x: ['-120%', '220%'] }
  const beamAnimate = prefersReducedMotion ? { opacity: 0.22 } : { opacity: [0.12, 0.48, 0.12] }

  return (
    <motion.div
      role="status"
      aria-live="polite"
      initial={entranceInitial}
      animate={entranceAnimate}
      transition={{ duration: 0.24, ease: easeOutQuint }}
      className={cn(
        'relative isolate grid place-items-center overflow-hidden rounded-xl bg-[#eef1f8] px-5 text-center text-[#000666] shadow-[inset_0_0_0_1px_rgba(0,6,102,0.06)]',
        compact ? 'py-4' : 'min-h-[132px] py-6',
        className,
      )}
    >
      <motion.span
        aria-hidden="true"
        animate={beamAnimate}
        transition={
          prefersReducedMotion ? undefined : { duration: 1.8, repeat: Infinity, ease: 'easeInOut' }
        }
        className="pointer-events-none absolute inset-x-8 top-0 h-px bg-gradient-to-r from-transparent via-[#2f62ff] to-transparent"
      />

      <div className="mx-auto grid max-w-xl justify-items-center gap-3">
        <div className="h-1.5 w-full max-w-[300px] overflow-hidden rounded-full bg-white/85 shadow-[inset_0_0_0_1px_rgba(0,6,102,0.08)]">
          <motion.span
            aria-hidden="true"
            animate={sweepAnimate}
            transition={
              prefersReducedMotion
                ? undefined
                : { duration: 1.35, repeat: Infinity, ease: sweepEase }
            }
            className="block h-full w-1/2 rounded-full bg-gradient-to-r from-[#000666] via-[#2f62ff] to-[#1a237e] shadow-[0_0_18px_rgba(47,98,255,0.35)]"
          />
        </div>

        <div className="flex items-center justify-center gap-1.5" aria-hidden="true">
          {[0, 1, 2].map((index) => (
            <motion.span
              key={index}
              animate={prefersReducedMotion ? { opacity: 0.7 } : { opacity: [0.35, 1, 0.35] }}
              transition={
                prefersReducedMotion
                  ? undefined
                  : { duration: 0.9, repeat: Infinity, delay: index * 0.14, ease: 'easeInOut' }
              }
              className="size-1.5 rounded-full bg-[#000666]"
            />
          ))}
        </div>

        <div className="grid gap-1">
          <p className="text-sm font-semibold text-[#000666]">{title}</p>
          {description ? (
            <p className="text-sm leading-6 text-[#5e6680]">{description}</p>
          ) : null}
        </div>
      </div>
    </motion.div>
  )
}
