import { onMounted, onUnmounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

/** 脏表单离开确认：路由离开与页面关闭两层拦截。 */
export function useDirtyGuard(isDirty: () => boolean): void {
  onBeforeRouteLeave(() => {
    if (!isDirty()) return true
    return window.confirm('有未保存的修改，离开将丢失。确认离开？')
  })

  const onBeforeUnload = (event: BeforeUnloadEvent): void => {
    if (!isDirty()) return
    event.preventDefault()
    event.returnValue = ''
  }

  onMounted(() => {
    window.addEventListener('beforeunload', onBeforeUnload)
  })
  onUnmounted(() => {
    window.removeEventListener('beforeunload', onBeforeUnload)
  })
}
