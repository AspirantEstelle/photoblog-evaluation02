from django.db import models
from django.utils import timezone

class Post(models.Model):
    author = models.ForeignKey('auth.User', on_delete=models.CASCADE)
    title = models.CharField(max_length=200)
    text = models.TextField()
    created_date = models.DateTimeField(default=timezone.now)
    published_date = models.DateTimeField(blank=True, null=True)
    image = models.ImageField(upload_to='images/%Y/%m/%d/', default='images/default_error.png')

    def publish(self):
        self.published_date = timezone.now()
        self.save()

    def __str__(self):
        return self.title


class UploadReceipt(models.Model):
    """A durable event identity survives deletion of its displayed post."""

    author = models.ForeignKey('auth.User', on_delete=models.CASCADE)
    event_id = models.UUIDField()
    request_digest = models.CharField(max_length=64)
    post = models.OneToOneField(Post, null=True, on_delete=models.SET_NULL,
                               related_name='upload_receipt')
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        constraints = [models.UniqueConstraint(
            fields=['author', 'event_id'], name='unique_author_edge_event')]

    def __str__(self):
        return str(self.event_id)
