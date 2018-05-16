package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.attachments.ContactAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.ContactReader;
import org.thoughtcrime.securesms.contactshare.ContactStream;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.events.PartProgressEvent;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class ContactAttachmentDownloadJob extends AttachmentDownloadJob {

  private static final String TAG = ContactAttachmentDownloadJob.class.getSimpleName();

  public ContactAttachmentDownloadJob(Context context, long messageId, AttachmentId attachmentId, boolean manual) {
    super(context, messageId, attachmentId, manual);
  }

  @Override
  void retrieveAttachment(long messageId,
                          final AttachmentId originalAttachmentId,
                          final Attachment attachment)
      throws IOException
  {

    AttachmentDatabase database       = DatabaseFactory.getAttachmentDatabase(context);
    File               attachmentFile = null;

    if (attachment.getDataUri() == null) {
      throw new IllegalStateException("This job assumes the data for the contact has already been persisted.");
    }

    try (InputStream contactStream = PartAuthority.getAttachmentStream(context, attachment.getDataUri())) {
      ContactReader contactReader = new ContactReader(contactStream);
      Contact       contact       = contactReader.getContact();

      if (contact.getAvatarState() == Contact.AvatarState.NONE) {
        return;
      }

      AttachmentId newAttachmentId = database.insertAttachment(messageId, new ContactAttachment(null), false);

      attachmentFile = createTempFile();

      SignalServiceAttachmentPointer pointer                 = createAttachmentPointer(attachment, contact.getAvatarSize());
      InputStream                    avatarStream            = messageReceiver.retrieveAttachment(pointer, attachmentFile, MAX_ATTACHMENT_SIZE, (total, progress) -> EventBus.getDefault().postSticky(new PartProgressEvent(attachment, total, progress)));
      InputStream                    contactWithAvatarStream = new ContactStream(contact, avatarStream);

      database.insertAttachmentsForPlaceholder(messageId, newAttachmentId, contactWithAvatarStream);

      database.deleteAttachment(originalAttachmentId);

    } catch (InvalidPartException | NonSuccessfulResponseCodeException | InvalidMessageException | MmsException e) {
      Log.w(TAG, e);
      markFailed(messageId, originalAttachmentId);
    } finally {
      if (attachmentFile != null) {
        //noinspection ResultOfMethodCallIgnored
        attachmentFile.delete();
      }
    }
  }
}