from django.contrib import admin
from .models import Post, UploadReceipt

admin.site.register(Post)


@admin.register(UploadReceipt)
class UploadReceiptAdmin(admin.ModelAdmin):
    list_display = ('event_id', 'author', 'post', 'created_at')
    readonly_fields = ('event_id', 'author', 'request_digest', 'post', 'created_at')

    def has_add_permission(self, request):
        return False

    def has_delete_permission(self, request, obj=None):
        return False
