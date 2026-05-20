import { ContentState } from '@/components/app/content-state'

export function AppRouteFallback() {
  return (
    <ContentState
      title="Loading workspace"
      description="Preparing the next module and its operational data."
      tone="loading"
      className="min-h-[280px]"
    />
  )
}

export function AuthRouteFallback() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-8">
      <div className="w-full max-w-lg">
        <ContentState
          title="Loading secure access"
          description="Preparing the authentication experience."
          tone="loading"
        />
      </div>
    </div>
  )
}
