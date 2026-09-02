<script setup lang="ts">
import { useRouter } from 'vue-router'
import { session } from './api/session'

const router = useRouter()

async function signOut() {
  await session.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <!-- The login screen is its own full-page layout: there is nothing to navigate to yet. -->
  <router-view v-if="!session.identity" />
  <div v-else class="shell">
    <nav class="sidebar">
      <div class="brand">
        most<small>exchange control</small>
      </div>
      <!--
        Grouped by what a click costs. The first two read and drive a live exchange; the middle
        four edit a draft that reaches nothing until it is published; the last two are the record
        and the accounts.
      -->
      <div class="nav">
        <router-link to="/status">Status</router-link>
        <router-link to="/operations">Operations</router-link>
        <router-link to="/books">Books</router-link>
        <div class="nav-gap"></div>
        <router-link to="/shards">Shards</router-link>
        <router-link to="/securities">Securities</router-link>
        <router-link to="/participants">Participants</router-link>
        <router-link to="/releases">Releases</router-link>
        <div class="nav-gap"></div>
        <router-link to="/schedules">Schedules</router-link>
        <router-link to="/audit">Audit</router-link>
        <router-link to="/operators">Operators</router-link>
      </div>
      <div class="identity">
        {{ session.identity.username }}
        <span class="pill">{{ session.identity.roles.join(', ') }}</span>
        <div><button class="link" @click="signOut">Sign out</button></div>
      </div>
    </nav>
    <main class="main"><router-view /></main>
  </div>
</template>
