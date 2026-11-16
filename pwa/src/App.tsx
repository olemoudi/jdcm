import { DataProvider, KidsProvider, SettingsProvider } from './hooks/app-state'
import { ServiceWorkerProvider } from './hooks/use-service-worker'
import { useRoute } from './lib/router'
import { AppUpdatePrompt } from './components/AppUpdatePrompt'
import { ErrorBoundary } from './components/ErrorBoundary'
import { MatchSheetHost } from './components/MatchSheet'
import { TabBar } from './components/TabBar'
import { ToastHost } from './components/ui'
import { GroupScreen } from './screens/GroupScreen'
import { HomeScreen } from './screens/HomeScreen'
import { KidEditor } from './screens/KidEditor'
import { SettingsScreen } from './screens/SettingsScreen'
import { TablesScreen } from './screens/TablesScreen'
import { TeamScreen } from './screens/TeamScreen'

/*
 * The service worker provider and the update prompt sit above everything, outside the error
 * boundary: a build that crashes while rendering can still be updated (PWA_UPDATE_PROTOCOL §5).
 */
export default function App() {
  return (
    <ServiceWorkerProvider>
      <SettingsProvider>
        <ErrorBoundary>
          <KidsProvider>
            <DataProvider>
              <MatchSheetHost>
                <Screens />
              </MatchSheetHost>
            </DataProvider>
          </KidsProvider>
        </ErrorBoundary>
        <AppUpdatePrompt />
        <ToastHost />
      </SettingsProvider>
    </ServiceWorkerProvider>
  )
}

function Screens() {
  const route = useRoute()
  let screen
  switch (route.name) {
    case 'tables': screen = <TablesScreen />; break
    case 'settings': screen = <SettingsScreen />; break
    case 'group': screen = <GroupScreen key={route.groupId} groupId={route.groupId} tab={route.tab} kidId={route.kidId} />; break
    case 'team': screen = <TeamScreen key={`${route.groupId}-${route.teamId}`} groupId={route.groupId} teamId={route.teamId} kidId={route.kidId} />; break
    case 'kid': screen = <KidEditor key={route.kidId ?? `new-${route.groupId}-${route.teamId}`} kidId={route.kidId} groupId={route.groupId} teamId={route.teamId} />; break
    default: screen = <HomeScreen />
  }
  return (
    <div className="mx-auto min-h-dvh max-w-lg px-safe">
      <div className="statusbar-strip fixed inset-x-0 top-0 z-40" aria-hidden="true" />
      {screen}
      {route.name !== 'kid' && <TabBar route={route} />}
    </div>
  )
}
