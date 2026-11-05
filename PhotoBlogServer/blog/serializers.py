from rest_framework import serializers
from .models import Post
from .validators import validate_uploaded_image

class PostSerializer(serializers.ModelSerializer):
    author_name = serializers.CharField(source='author.username', read_only=True)
    image = serializers.ImageField(required=True)

    class Meta:
        model = Post
        fields = ('id', 'author', 'author_name', 'title', 'text', 'created_date', 'published_date', 'image')
        read_only_fields = ('id', 'author', 'created_date')

    def validate_image(self, value):
        return validate_uploaded_image(value)
