import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { createAppRouter } from './app/router'
import { setupRouterGuards } from './app/routerGuards'
import { initRuntimeConfig } from './app/runtimeConfig'
import './styles/reset.css'
import './styles/base.css'

initRuntimeConfig()

const app = createApp(App)
app.use(createPinia())

const router = createAppRouter()
setupRouterGuards(router)
app.use(router)

app.mount('#app')
