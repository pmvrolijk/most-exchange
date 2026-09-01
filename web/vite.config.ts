import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Everything under /api is proxied to the control plane, so the browser sees one origin.
//
// That is not just convenience. The session is a cookie and the CSRF defence is a cookie the SPA
// copies into a header; both are same-origin mechanisms. Serving the SPA from a second origin
// would mean CORS, credentialed cross-origin requests and SameSite=None on the session cookie --
// three loosened settings to work around a problem a proxy removes. Production serves the built
// assets from behind the same host for the same reason.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // The control plane's default port. Change it here rather than through an env var: a
        // dev proxy target is a two-line edit and adding @types/node to read one variable is not.
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
})
