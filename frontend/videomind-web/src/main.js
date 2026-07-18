import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles.css'
import Root from './Root.vue'

createApp(Root).use(ElementPlus).mount('#app')

