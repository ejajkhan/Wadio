use jni::objects::{JObject, JValue};

pub fn play_video(rel_path: &str) {
    #[cfg(not(target_os = "android"))]
    { let _ = rel_path; return; }
    #[cfg(target_os = "android")]
    {
        let ctx = ndk_context::android_context();
        let Ok(vm) = (unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }) else { return };
        let Ok(mut env) = vm.attach_current_thread() else { return };
        let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
        let Ok(jstr) = env.new_string(rel_path) else { return };
        let _ = env.call_method(&activity, "playVideoInternally", "(Ljava/lang/String;)V", &[JValue::Object(&jstr)]);
    }
}
