from django.core.exceptions import ValidationError


def validate_uploaded_image(value):
    if value.size > 5 * 1024 * 1024:
        raise ValidationError('이미지는 5 MB 이하여야 합니다.')
    if value.image.format not in ('JPEG', 'PNG', 'GIF', 'WEBP'):
        raise ValidationError('JPG, PNG, GIF, WEBP 이미지를 선택하세요.')
    return value
