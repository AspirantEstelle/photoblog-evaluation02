from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [('blog', '0003_alter_post_image')]
    operations = [
        migrations.AlterField(
            model_name='post', name='image',
            field=models.ImageField(default='images/default_error.png', upload_to='images/%Y/%m/%d/'),
        ),
    ]
