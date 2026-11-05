from django import forms
from django.core.files.uploadedfile import UploadedFile
from .models import Post
from .validators import validate_uploaded_image

class PostForm(forms.ModelForm):
    class Meta:
        model = Post
        fields = ('title', 'text','image')

    def clean_image(self):
        image = self.cleaned_data['image']
        if isinstance(image, UploadedFile):
            validate_uploaded_image(image)
        return image
