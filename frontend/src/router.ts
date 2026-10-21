import { createRouter, createWebHistory } from 'vue-router'
import NewTaskView from './views/NewTaskView.vue'
import TaskListView from './views/TaskListView.vue'
import TaskDetailView from './views/TaskDetailView.vue'
import SettingsView from './views/SettingsView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: NewTaskView },
    { path: '/tasks', component: TaskListView },
    { path: '/tasks/:id', component: TaskDetailView },
    { path: '/settings', component: SettingsView },
  ],
})
