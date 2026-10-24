use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use zeroize::Zeroize;

pub struct AuthEngine {
    password_hash: Option<String>,
    unlocked: bool,
    press_start_time: Option<f64>,
    show_password_modal: bool,
}

impl AuthEngine {
    pub fn new(persisted_hash: Option<String>) -> Self {
        Self { password_hash: persisted_hash, unlocked: false, press_start_time: None, show_password_modal: false }
    }
    pub fn is_unlocked(&self) -> bool { self.unlocked }
    /// Reconcile the Android window flag after returning from Recents or another activity.
    pub fn refresh_window_security(&self) { Self::apply_flag_secure(self.unlocked || self.show_password_modal); }
    pub fn needs_password_setup(&self) -> bool { self.password_hash.is_none() }
    pub fn should_show_prompt(&self) -> bool { self.show_password_modal }
    pub fn dismiss_prompt(&mut self) { self.show_password_modal = false; self.press_start_time = None; if !self.unlocked { Self::apply_flag_secure(false); } }
    pub fn is_pressing_header(&self) -> bool { self.press_start_time.is_some() }
    pub fn lock(&mut self) { self.unlocked = false; self.show_password_modal = false; self.press_start_time = None; Self::apply_flag_secure(false); }

    pub fn update_header_touch(&mut self, is_pressed: bool, current_time: f64) {
        if self.unlocked { return; }
        match (is_pressed, self.press_start_time) {
            (true, None) => self.press_start_time = Some(current_time),
            (true, Some(start)) if current_time - start >= 3.0 => {
                self.show_password_modal = true;
                self.press_start_time = None;
                Self::apply_flag_secure(true);
            }
            (true, Some(_)) => {},
            (false, _) => self.press_start_time = None,
        }
    }

    /// Perform the expensive Argon2 operation. Safe to call on a worker thread.
    /// Returns the hash to persist on success, and zeroizes the supplied password.
    pub fn check_password(persisted_hash: Option<String>, mut password: Vec<u8>) -> Result<String, ()> {
        let result = (|| {
            match persisted_hash {
                Some(saved_hash) => {
                    let parsed = PasswordHash::new(&saved_hash).map_err(|_| ())?;
                    Argon2::default().verify_password(&password, &parsed).map_err(|_| ())?;
                    Ok(saved_hash)
                }
                None => {
                    let salt = SaltString::generate(&mut OsRng);
                    Argon2::default().hash_password(&password, &salt).map(|hash| hash.to_string()).map_err(|_| ())
                }
            }
        })();
        password.zeroize();
        result
    }

    /// Unlock only after Android BiometricPrompt has authenticated a biometric or device credential.
    /// A configured PIN verifier must exist; biometric auth can never initialize a vault by itself.
    pub fn unlock_with_biometric(&mut self) -> bool {
        if self.password_hash.is_none() { return false; }
        self.unlocked = true;
        self.show_password_modal = false;
        self.press_start_time = None;
        Self::apply_flag_secure(true);
        true
    }

    pub fn unlock_with_hash(&mut self, hash: String) {
        self.password_hash = Some(hash);
        self.unlocked = true;
        self.show_password_modal = false;
        self.press_start_time = None;
        Self::apply_flag_secure(true);
    }

    pub fn verify_password(&mut self, password: Vec<u8>) -> bool {
        match Self::check_password(self.password_hash.clone(), password) {
            Ok(hash) => { self.unlock_with_hash(hash); true }
            Err(()) => false,
        }
    }
    pub fn get_hash_for_storage(&self) -> Option<String> { self.password_hash.clone() }

    fn apply_flag_secure(enable: bool) {
        #[cfg(target_os = "android")]
        {
            let ctx = ndk_context::android_context();
            let Ok(vm) = (unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }) else { return };
            let Ok(mut env) = vm.attach_current_thread() else { return };
            let activity = unsafe { jni::objects::JObject::from_raw(ctx.context().cast()) };
            let _ = env.call_method(&activity, "setWindowSecurity", "(Z)V", &[jni::objects::JValue::Bool(enable as u8)]);
        }
        #[cfg(not(target_os = "android"))]
        let _ = enable;
    }
}
