import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  // 候选人查询门户：免登录，凭限时凭证访问
  { path: '/portal', name: 'portal', component: () => import('../views/CandidatePortalView.vue') },
  { path: '/', redirect: '/subjects' },
  { path: '/subjects', name: 'subjects', component: () => import('../views/SubjectView.vue') },
  { path: '/tickets', name: 'tickets', component: () => import('../views/TicketListView.vue') },
  { path: '/tickets/:id', name: 'ticket-detail', component: () => import('../views/TicketDetailView.vue') },
  { path: '/identity', name: 'identity', component: () => import('../views/IdentityReviewView.vue') },
  { path: '/replies', name: 'replies', component: () => import('../views/ExternalReplyView.vue') },
  { path: '/lineage', name: 'lineage', component: () => import('../views/LineageView.vue') }
]

const router = createRouter({ history: createWebHashHistory(), routes })

// 候选人门户与登录页不要求登录态；其余操作台页面需要 HTTP Basic 登录
const PUBLIC_ROUTES = ['login', 'portal']

router.beforeEach((to) => {
  const authed = !!localStorage.getItem('rtbf-auth')
  if (!authed && !PUBLIC_ROUTES.includes(to.name)) {
    return { name: 'login' }
  }
})

export default router
