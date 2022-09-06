use crossbeam::channel::{Sender, Receiver};
use webrender::webrender_api::*;

pub type FrameId = u64;

pub struct DiagnosticMessage {
    pub frame_id: FrameId,
}

pub type DiagnosticSender = Sender<DiagnosticMessage>;
pub type DiagnosticReceiver = Receiver<DiagnosticMessage>;

pub fn create_diagnostic_chanels() -> (DiagnosticSender, DiagnosticReceiver) {
    crossbeam::crossbeam_channel::unbounded()
}

struct OnRenderCallback {
    frame_id: FrameId,
    diagnostic_sender: DiagnosticSender
}

impl NotificationHandler for OnRenderCallback {
    fn notify(&self, when: Checkpoint) {
        self.diagnostic_sender.send(DiagnosticMessage { frame_id: self.frame_id });
    }
}

pub fn notify_on_render(txn: &mut Transaction, frame_id: FrameId, diagnostic_sender: &DiagnosticSender) {
    txn.notify(NotificationRequest::new(Checkpoint::FrameRendered,
                                        Box::new(OnRenderCallback {
                                            frame_id,
                                            diagnostic_sender: diagnostic_sender.clone()
                                        })));
}

pub fn receive_frame_id(diagnostic_receiver: &DiagnosticReceiver) -> Option<FrameId> {
    diagnostic_receiver.try_iter().last().map(|diagnostic_message| {
        diagnostic_message.frame_id
    })
}