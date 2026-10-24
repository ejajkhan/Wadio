use crate::db::MediaRecord;
use crate::thumbnails::ThumbnailPool;
use egui::{Color32, Context, ScrollArea, Ui, Vec2};
use std::collections::HashSet;
use std::path::Path;

pub fn render_media_grid(
    ui: &mut Ui,
    records: &[MediaRecord],
    vault_base: &Path,
    thumbs: &mut ThumbnailPool,
    columns: usize,
    tile_size: f32,
    spacing: f32,
    selected_ids: &HashSet<String>,
    long_press_start: &mut Option<(String, f64)>,
    suppress_click_id: &mut Option<String>,
    mut on_click: impl FnMut(&MediaRecord),
    mut on_long_press: impl FnMut(&MediaRecord),
) {
    let ctx: Context = ui.ctx().clone();
    let total_records = records.len();
    let total_rows = (total_records + columns - 1) / columns;
    let row_height = tile_size + spacing;
    let (now, pointer_down) = ctx.input(|i| (i.time, i.pointer.primary_down()));

    ScrollArea::vertical()
        .auto_shrink([false; 2])
        .show_rows(ui, row_height, total_rows, |ui, row_range| {
            for row in row_range {
                ui.horizontal(|ui| {
                    for col in 0..columns {
                        let idx = row * columns + col;
                        if idx >= total_records { break; }
                        let item = &records[idx];
                        let original_path = vault_base.join(&item.relative_path);
                        let gif_thumb = vault_base.join(".thumbnails").join(format!("{}.gif", item.id));
                        let full_path = if item.file_name.to_ascii_lowercase().ends_with(".gif") && gif_thumb.is_file() {
                            gif_thumb
                        } else { original_path };
                        let (rect, response) = ui.allocate_exact_size(Vec2::splat(tile_size), egui::Sense::click());

                        if response.hovered() && pointer_down {
                            match long_press_start.as_ref() {
                                Some((id, _)) if id == &item.id => {
                                    if let Some((_, started)) = long_press_start.as_ref() {
                                        if now - *started >= 2.0 && suppress_click_id.as_deref() != Some(item.id.as_str()) {
                                            *suppress_click_id = Some(item.id.clone());
                                            *long_press_start = Some((item.id.clone(), f64::INFINITY));
                                            on_long_press(item);
                                        }
                                    }
                                }
                                _ => *long_press_start = Some((item.id.clone(), now)),
                            }
                        }
                        if response.clicked() {
                            if suppress_click_id.as_deref() == Some(item.id.as_str()) {
                                *suppress_click_id = None;
                            } else {
                                on_click(item);
                            }
                        }

                        if let Some(texture) = thumbs.get_or_request(&item.id, full_path, &ctx) {
                            ui.painter().image(texture.id(), rect, egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)), Color32::WHITE);
                        } else {
                            ui.painter().rect_filled(rect, 3.0, Color32::from_gray(30));
                            if item.file_name.to_ascii_lowercase().ends_with(".gif") {
                                ui.painter().text(rect.center(), egui::Align2::CENTER_CENTER, "GIF", egui::FontId::proportional(14.0), Color32::LIGHT_GRAY);
                            }
                        }
                        if selected_ids.contains(&item.id) {
                            ui.painter().rect_filled(rect, 3.0, Color32::from_rgba_unmultiplied(20, 110, 220, 90));
                            ui.painter().rect_stroke(rect, 3.0, egui::Stroke::new(2.0, Color32::from_rgb(80, 170, 255)));
                            ui.painter().text(rect.right_top() + egui::vec2(-8.0, 8.0), egui::Align2::RIGHT_TOP, "✓", egui::FontId::proportional(22.0), Color32::WHITE);
                        }
                    }
                });
            }
        });
    if !pointer_down {
        *long_press_start = None;
        // If a long-press ended away from its original tile, do not suppress a later tap.
        *suppress_click_id = None;
    }
}
