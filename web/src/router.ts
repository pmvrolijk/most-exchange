import { createRouter, createWebHistory } from 'vue-router'
import { session } from './api/session'

const routes = [
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue') },
  { path: '/', redirect: '/status' },
  { path: '/status', name: 'status', component: () => import('./views/StatusView.vue') },
  { path: '/operations', name: 'operations', component: () => import('./views/OperationsView.vue') },
  { path: '/books', name: 'books', component: () => import('./views/BooksView.vue') },
  { path: '/shards', name: 'shards', component: () => import('./views/ShardsView.vue') },
  { path: '/securities', name: 'securities', component: () => import('./views/SecuritiesView.vue') },
  { path: '/participants', name: 'participants', component: () => import('./views/ParticipantsView.vue') },
  { path: '/releases', name: 'releases', component: () => import('./views/ReleasesView.vue') },
  { path: '/schedules', name: 'schedules', component: () => import('./views/SchedulesView.vue') },
  { path: '/operators', name: 'operators', component: () => import('./views/UsersView.vue') },
  { path: '/audit', name: 'audit', component: () => import('./views/AuditView.vue') },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * The guard is a convenience, not the protection.
 *
 * Every route but `/login` needs an identity, and the check is here so the operator sees a login
 * form rather than seven empty tables. What actually protects the data is the server answering 401
 * — a guard in a bundle the browser downloaded can always be edited by whoever downloaded it.
 */
router.beforeEach(async (to) => {
  if (!session.resolved) await session.resolve()
  if (to.name === 'login') return session.identity ? { name: 'status' } : true
  if (!session.identity) return { name: 'login', query: { next: to.fullPath } }
  return true
})
