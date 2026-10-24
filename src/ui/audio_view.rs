use crate::platform::audio::{STREAM_ALARM, STREAM_MUSIC, STREAM_SYSTEM, StreamState};
use egui::{Slider, Ui};

pub fn render_audio_controls(
    ui: &mut Ui,
    states: &[StreamState],
    mut on_change: impl FnMut(i32, i32),
) {
    ui.heading("System Sound");
    ui.add_space(8.0);

    for s in states {
        let title = match s.stream_type {
            STREAM_MUSIC => "Media Volume",
            STREAM_SYSTEM => "System Volume",
            STREAM_ALARM => "Alarm Volume",
            _ => "Unknown Stream",
        };

        ui.group(|ui| {
            ui.horizontal(|ui| {
                ui.label(title);
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.label(format!("{}/{}", s.current, s.max));
                });
            });

            let mut val = s.current;
            if ui.add(Slider::new(&mut val, s.min..=s.max).show_value(false)).changed() {
                on_change(s.stream_type, val);
            }
        });
        ui.add_space(6.0);
    }
}
