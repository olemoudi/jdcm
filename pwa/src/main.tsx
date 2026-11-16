import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { listenForInstallPrompt } from './lib/install-prompt'
import './styles/index.css'

// Chrome may offer its install prompt before React mounts: catch it now.
listenForInstallPrompt()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
