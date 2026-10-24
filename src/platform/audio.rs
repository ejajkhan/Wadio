use jni::objects::{JObject, JValue};
use parking_lot::{Mutex, RwLock};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const STREAM_VOICE_CALL: i32 = 0;
pub const STREAM_SYSTEM: i32 = 1;
pub const STREAM_RING: i32 = 2;
pub const STREAM_MUSIC: i32 = 3;
pub const STREAM_ALARM: i32 = 4;

#[derive(Debug, Clone, Copy)]
pub struct StreamState {
    pub stream_type: i32,
    pub current: i32,
    pub min: i32,
    pub max: i32,
}

pub struct AndroidAudioController {
    states: Arc<RwLock<Vec<StreamState>>>,
    last_poll: Mutex<Option<Instant>>,
}

impl AndroidAudioController {
    pub fn new() -> Self {
        let initial = vec![
            StreamState { stream_type: STREAM_MUSIC, current: 0, min: 0, max: 15 },
            StreamState { stream_type: STREAM_SYSTEM, current: 0, min: 0, max: 7 },
            StreamState { stream_type: STREAM_ALARM, current: 0, min: 0, max: 7 },
        ];
        Self { states: Arc::new(RwLock::new(initial)), last_poll: Mutex::new(None) }
    }

    pub fn get_states(&self) -> Vec<StreamState> { self.states.read().clone() }

    pub fn set_volume(&self, stream_type: i32, value: i32) {
        #[cfg(target_os = "android")]
        {
            let ctx = ndk_context::android_context();
            let Ok(vm) = (unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }) else { return };
            let Ok(mut env) = vm.attach_current_thread() else { return };
            let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
            let result = env.call_method(&activity, "setStreamVolume", "(II)V",
                &[JValue::Int(stream_type), JValue::Int(value)]);
            if result.is_ok() {
                if let Some(target) = self.states.write().iter_mut().find(|s| s.stream_type == stream_type) {
                    target.current = value;
                }
            }
        }
        #[cfg(not(target_os = "android"))]
        { let _ = (stream_type, value); }
    }

    pub fn poll_system_volumes(&self) {
        #[cfg(target_os = "android")]
        {
            {
                let mut last = self.last_poll.lock();
                if last.as_ref().map(|t| t.elapsed() < Duration::from_millis(500)).unwrap_or(false) { return; }
                *last = Some(Instant::now());
            }
            let ctx = ndk_context::android_context();
            let Ok(vm) = (unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }) else { return };
            let Ok(mut env) = vm.attach_current_thread() else { return };
            let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
            let mut states = self.states.write();
            for state in states.iter_mut() {
                state.current = env.call_method(&activity, "getStreamVolume", "(I)I", &[JValue::Int(state.stream_type)])
                    .and_then(|v| v.i()).unwrap_or(state.current);
                state.max = env.call_method(&activity, "getStreamMaxVolume", "(I)I", &[JValue::Int(state.stream_type)])
                    .and_then(|v| v.i()).unwrap_or(state.max);
                state.min = env.call_method(&activity, "getStreamMinVolume", "(I)I", &[JValue::Int(state.stream_type)])
                    .and_then(|v| v.i()).unwrap_or(state.min);
            }
        }
    }
}
