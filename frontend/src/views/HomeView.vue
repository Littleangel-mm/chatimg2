<script setup>
import { defineOptions } from 'vue'
import GeneratorPanel from '../components/GeneratorPanel.vue'
import HistoryGallery from '../components/HistoryGallery.vue'
import KeyModal from '../components/KeyModal.vue'

defineOptions({ name: 'home' })
</script>

<template>
  <main>
    <section class="hero">
      <div class="container hero-inner">
        <div class="hero-badge">AI GENERATOR</div>
        <h1 class="hero-title">
          Image to AI:<br />
          <span class="gradient-text">Turn Any Image into AI Art</span>
        </h1>
        <p class="hero-desc">
          Upload a photo or enter a prompt, choose a mode, and generate polished results in seconds.
        </p>
        <GeneratorPanel />
      </div>
    </section>

    <section class="features">
      <div class="container features-grid">
        <div class="feature-card card">
          <div class="feature-icon">✨</div>
          <h3>Text to Image</h3>
          <p>Turn a prompt into fresh visuals in seconds.</p>
        </div>
        <div class="feature-card card">
          <div class="feature-icon">🖼</div>
          <h3>Image to Image</h3>
          <p>Transform a photo into a new style while keeping the subject.</p>
        </div>
        <div class="feature-card card">
          <div class="feature-icon">⚡</div>
          <h3>One Clean Hub</h3>
          <p>Both workflows in one simple interface.</p>
        </div>
      </div>
    </section>

    <HistoryGallery />
    <KeyModal />

    <footer class="footer">
      <div class="container footer-inner">
        <span class="footer-logo">img2.ai</span>
        <span class="footer-copy">© 2026 img2.ai, All rights reserved</span>
      </div>
    </footer>
  </main>
</template>

<style scoped>
.hero {
  padding: 60px 0 80px;
  text-align: center;
  background: radial-gradient(ellipse 80% 50% at 50% -20%, rgba(124, 92, 255, 0.15), transparent);
}

.hero-badge {
  display: inline-block;
  padding: 6px 14px;
  background: rgba(124, 92, 255, 0.15);
  border: 1px solid rgba(124, 92, 255, 0.3);
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: #b49cff;
  margin-bottom: 24px;
}

.hero-title {
  font-size: clamp(32px, 5vw, 52px);
  font-weight: 800;
  line-height: 1.15;
  margin-bottom: 20px;
}

.gradient-text {
  background: var(--gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.hero-desc {
  max-width: 560px;
  margin: 0 auto 40px;
  color: var(--text-muted);
  font-size: 17px;
  line-height: 1.6;
}

.features {
  padding: 60px 0;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
}

.feature-card { padding: 28px; }
.feature-icon { font-size: 32px; margin-bottom: 16px; }
.feature-card h3 { font-size: 18px; margin-bottom: 8px; }
.feature-card p { color: var(--text-muted); font-size: 14px; line-height: 1.6; }

.footer { padding: 32px 0; border-top: 1px solid var(--border); }

.footer-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.footer-logo { font-weight: 700; font-size: 16px; }
.footer-copy { color: var(--text-muted); font-size: 13px; }
</style>
