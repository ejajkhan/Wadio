use wadio::auth::AuthEngine;

#[test]
fn test_password_hashing_and_verification() {
    let mut auth = AuthEngine::new(None);
    let pass = b"CorrectHorseBatteryStaple".to_vec();

    assert!(auth.verify_password(pass.clone()));
    let hash = auth.get_hash_for_storage().expect("Must have hash");

    let mut re_auth = AuthEngine::new(Some(hash));
    assert!(re_auth.verify_password(pass));
    assert!(!re_auth.verify_password(b"WrongPassword".to_vec()));
}

#[test]
fn test_three_second_timing_threshold() {
    let mut auth = AuthEngine::new(None);
    
    auth.update_header_touch(true, 10.0);
    assert!(!auth.should_show_prompt());

    auth.update_header_touch(true, 12.9);
    assert!(!auth.should_show_prompt());

    auth.update_header_touch(true, 13.0);
    assert!(auth.should_show_prompt());
}

#[test]
fn test_touch_interruption_resets_counter() {
    let mut auth = AuthEngine::new(None);
    auth.update_header_touch(true, 10.0);
    auth.update_header_touch(true, 12.5);
    auth.update_header_touch(false, 12.6);
    auth.update_header_touch(true, 13.0);
    auth.update_header_touch(true, 13.5);
    assert!(!auth.should_show_prompt());
}

#[test]
fn biometric_unlock_requires_a_configured_pin_verifier() {
    let mut setup_required = AuthEngine::new(None);
    assert!(!setup_required.unlock_with_biometric());
    assert!(!setup_required.is_unlocked());

    let hash = AuthEngine::check_password(None, b"123456".to_vec()).unwrap();
    assert!(!hash.contains("123456"), "the PIN must not be stored in plaintext");
    let mut configured = AuthEngine::new(Some(hash));
    assert!(configured.unlock_with_biometric());
    assert!(configured.is_unlocked());
}
