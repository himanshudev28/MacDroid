import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import MirrorWindow from './components/MirrorWindow.jsx'
import './index.css'

// The pop-out mirror window loads the same bundle at the #mirror route.
const isMirror = window.location.hash === '#mirror'

createRoot(document.getElementById('root')).render(
  <React.StrictMode>{isMirror ? <MirrorWindow /> : <App />}</React.StrictMode>
)
