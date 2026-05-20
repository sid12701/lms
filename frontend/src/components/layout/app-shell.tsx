import { useEffect, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/features/auth/auth-context'
import { AppShellHeader } from './app-shell-header'
import { AppShellSidebar } from './app-shell-sidebar'

const pageTransitionEase = [0.22, 1, 0.36, 1] as const

export function AppShell() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const prefersReducedMotion = useReducedMotion()
  const [reportsOpen, setReportsOpen] = useState(location.pathname.startsWith('/reports'))

  useEffect(() => {
    if (location.pathname.startsWith('/reports')) {
      setReportsOpen(true)
    }
  }, [location.pathname])

  return (
    <div className="min-h-screen bg-[#f8f9fc] lg:grid lg:grid-cols-[260px_minmax(0,1fr)]">
      <AppShellSidebar
        user={user}
        reportsOpen={reportsOpen}
        onToggleReports={() => setReportsOpen((current) => !current)}
        onLogout={logout}
        reportsActive={location.pathname.startsWith('/reports')}
      />

      <main className="min-w-0 px-5 py-7 lg:px-8">
        <div className="flex max-w-[1100px] flex-col gap-6">
          <AppShellHeader user={user} />

          <AnimatePresence mode="wait" initial={false}>
            <motion.section
              key={location.pathname}
              className="min-w-0"
              initial={prefersReducedMotion ? false : { opacity: 0, y: 10, filter: 'blur(3px)' }}
              animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
              exit={prefersReducedMotion ? { opacity: 0 } : { opacity: 0, y: -4, filter: 'blur(2px)' }}
              transition={{ duration: 0.22, ease: pageTransitionEase }}
            >
              <Outlet />
            </motion.section>
          </AnimatePresence>
        </div>
      </main>
    </div>
  )
}
