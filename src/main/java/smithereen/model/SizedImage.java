package smithereen.model;

import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.text.Format;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import smithereen.Config;
import smithereen.activitypub.objects.LocalImage;
import smithereen.model.media.SizedImageURLs;
import smithereen.storage.ImgProxy;
import smithereen.text.TextProcessor;
import spark.utils.StringUtils;

public interface SizedImage{
	URI getUriForSizeAndFormat(Type size, Format format, boolean is2x, boolean useFallback);
	Dimensions getOriginalDimensions();
	URI getOriginalURI();

	default Dimensions getDimensionsForSize(Type size, Dimensions dimensions){
		return size.getResizedDimensions(dimensions);
	}

	default Dimensions getDimensionsForSize(Type size){
		return getDimensionsForSize(size, getOriginalDimensions());
	}

	default List<SizedImageURLs> getURLsForPhotoViewer(){
		ArrayList<SizedImageURLs> urls=new ArrayList<>();
		Dimensions origSize=getOriginalDimensions();
		boolean isLocal=this instanceof LocalImage;
		for(Type t:List.of(Type.PHOTO_SMALL, Type.PHOTO_MEDIUM, Type.PHOTO_LARGE, Type.PHOTO_ORIGINAL)){
			Dimensions size=getDimensionsForSize(t);
			urls.add(new SizedImageURLs(t.suffix, size.width, size.height, Objects.toString(getUriForSizeAndFormat(t, Format.WEBP, false, isLocal)), Objects.toString(getUriForSizeAndFormat(t, Format.JPEG, false, isLocal))));
			if(size.width>=origSize.width && size.height>=origSize.height)
				break;
		}
		return urls;
	}

	default String getUrlForSizeAndFormat(String size, String format){
		return getUriForSizeAndFormat(Type.fromSuffix(size), Format.fromFileExtension(format)).toString();
	}

	default URI getUriForSizeAndFormat(Type size, Format format){
		return getUriForSizeAndFormat(size, format, false, true);
	}

	default String generateHTML(Type size, List<String> additionalClasses, String styleAttr, int width, int height, boolean add2x, String altText){
		StringBuilder sb=new StringBuilder("<picture>");
		appendHtmlForFormat(size, Format.WEBP, sb, add2x);
		appendHtmlForFormat(size, Format.JPEG, sb, add2x);
		sb.append("<img src=\"");
		sb.append(getUriForSizeAndFormat(size, Format.JPEG));
		sb.append('"');
		if(additionalClasses!=null && !additionalClasses.isEmpty()){
			sb.append(" class=\"");
			boolean first=true;
			for(String cls:additionalClasses){
				if(!first){
					sb.append(' ');
				}else{
					first=false;
				}
				sb.append(cls);
			}
			sb.append('"');
		}
		if(StringUtils.isNotEmpty(styleAttr)){
			sb.append(" style=\"");
			sb.append(styleAttr);
			sb.append('"');
		}
		if(width>0){
			sb.append(" width=\"");
			sb.append(width);
			sb.append('"');
		}
		if(height>0){
			sb.append(" height=\"");
			sb.append(height);
			sb.append('"');
		}
		if(StringUtils.isNotEmpty(altText)){
			sb.append(" alt=\"");
			sb.append(TextProcessor.escapeHTML(altText));
			sb.append('"');
		}
		sb.append("/></picture>");
		return sb.toString();
	}

	private void appendHtmlForFormat(Type size, Format format, StringBuilder sb, boolean add2x){
		sb.append("<source srcset=\"");
		sb.append(getUriForSizeAndFormat(size, format));
		if(add2x){
			sb.append(", ");
			sb.append(getUriForSizeAndFormat(size.get2xType(), format, true, true));
			sb.append(" 2x");
		}
		sb.append("\" type=\"");
		sb.append(format.contentType());
		sb.append("\"/>");
	}

	enum Type{
		/**
		 * Photos: 100x100
		 */
		PHOTO_THUMB_SMALL("s", 100, 100, ImgProxy.ResizingType.FIT),
		/**
		 * Photos: 320x320
		 */
		PHOTO_THUMB_MEDIUM("m", 320, 320, ImgProxy.ResizingType.FIT),
		/**
		 * Photos: 640x640
		 */
		PHOTO_SMALL("x", 640, 640, ImgProxy.ResizingType.FIT),
		/**
		 * Photos: 800x800
		 */
		PHOTO_MEDIUM("y", 800, 800, ImgProxy.ResizingType.FIT),
		/**
		 * Photos: 1280x1280
		 */
		PHOTO_LARGE("z", 1280, 1280, ImgProxy.ResizingType.FIT),
		/**
		 * Photos: 2560x2560
		 */
		PHOTO_ORIGINAL("w", 2560, 2560, ImgProxy.ResizingType.FIT),

		/**
		 * Avatars: 200xH
		 */
		AVA_RECT("cr", 200, 2560, ImgProxy.ResizingType.FIT),
		/**
		 * Avatars: 400xH
		 */
		AVA_RECT_LARGE("dr", 400, 2560, ImgProxy.ResizingType.FIT),

		/**
		 * Avatars: 50x50 square
		 */
		AVA_SQUARE_SMALL("a", 50, 50, ImgProxy.ResizingType.FILL),
		/**
		 * Avatars: 100x100 square
		 */
		AVA_SQUARE_MEDIUM("b", 100, 100, ImgProxy.ResizingType.FILL),
		/**
		 * Avatars: 200x200 square
		 */
		AVA_SQUARE_LARGE("c", 200, 200, ImgProxy.ResizingType.FILL),
		/**
		 * Avatars: 400x400 square
		 */
		AVA_SQUARE_XLARGE("d", 400, 400, ImgProxy.ResizingType.FILL);

		private final String suffix;
		private final int maxWidth, maxHeight;
		private final ImgProxy.ResizingType resizingType;

		Type(String suffix, int maxWidth, int maxHeight, ImgProxy.ResizingType resizingType){
			this.suffix=suffix;
			this.maxWidth=maxWidth;
			this.maxHeight=maxHeight;
			this.resizingType=resizingType;
		}

		public String suffix(){
			return suffix;
		}

		public int getMaxWidth(){
			return maxWidth;
		}

		public int getMaxHeight(){
			return maxHeight;
		}

		public ImgProxy.ResizingType getResizingType(){
			return resizingType;
		}

		public Dimensions getResizedDimensions(Dimensions in){
			if(resizingType==ImgProxy.ResizingType.FILL){
				return new Dimensions(maxWidth, maxHeight);
			}
			float ratio;
			if(maxWidth==maxHeight){
				ratio=maxWidth/(float) Math.max(in.width, in.height);
			}else{
				ratio=maxWidth/(float) in.width;
			}
			if(this!=AVA_RECT && this!=AVA_RECT_LARGE)
				ratio=Math.min(1f, ratio);
			return new Dimensions(Math.round(in.width*ratio), Math.round(in.height*ratio));
		}

		public Type get2xType(){
			return switch(this){
				case PHOTO_THUMB_SMALL -> PHOTO_THUMB_MEDIUM;
				case PHOTO_THUMB_MEDIUM -> PHOTO_SMALL;
				case PHOTO_SMALL -> PHOTO_MEDIUM;
				case PHOTO_MEDIUM -> PHOTO_LARGE;
				case PHOTO_LARGE, PHOTO_ORIGINAL -> PHOTO_ORIGINAL;

				case AVA_RECT, AVA_RECT_LARGE -> AVA_RECT_LARGE;
				case AVA_SQUARE_SMALL -> AVA_SQUARE_MEDIUM;
				case AVA_SQUARE_MEDIUM -> AVA_SQUARE_LARGE;
				case AVA_SQUARE_LARGE, AVA_SQUARE_XLARGE -> AVA_SQUARE_XLARGE;
			};
		}

		public boolean isRect(){
			return this==AVA_RECT || this==AVA_RECT_LARGE;
		}

		public static Type fromSuffix(String s){
			return switch(s){
				case "s" -> PHOTO_THUMB_SMALL;
				case "m" -> PHOTO_THUMB_MEDIUM;
				case "x" -> PHOTO_SMALL;
				case "y" -> PHOTO_MEDIUM;
				case "z" -> PHOTO_LARGE;
				case "w" -> PHOTO_ORIGINAL;
				case "cr" -> AVA_RECT;
				case "dr" -> AVA_RECT_LARGE;
				case "a" -> AVA_SQUARE_SMALL;
				case "b" -> AVA_SQUARE_MEDIUM;
				case "c" -> AVA_SQUARE_LARGE;
				case "d" -> AVA_SQUARE_XLARGE;
				default -> {
					if(Config.DEBUG){
						throw new IllegalArgumentException("Invalid image size '"+s+"'");
					}else{
						yield null;
					}
				}
			};
		}
	}

	enum Format{
		JPEG,
		WEBP,
		AVIF,
		PNG;

		public String fileExtension(){
			return switch(this){
				case JPEG -> "jpg";
				case WEBP -> "webp";
				case AVIF -> "avif";
				case PNG -> "png";
			};
		}

		public String contentType(){
			return switch(this){
				case JPEG -> "image/jpeg";
				case WEBP -> "image/webp";
				case AVIF -> "image/avif";
				case PNG -> "image/png";
			};
		}

		public static Format fromFileExtension(String ext){
			return switch(ext){
				case "jpg", "jpeg" -> JPEG;
				case "webp" -> WEBP;
				case "avif" -> AVIF;
				case "png" -> PNG;
				default -> throw new IllegalStateException("Unexpected value: "+ext);
			};
		}
	}

	class Dimensions{
		public static final Dimensions UNKNOWN=new Dimensions(-1, -1);

		public final int width, height;

		public Dimensions(int width, int height){
			this.width=width;
			this.height=height;
		}
	}

	enum Rotation{
		@SerializedName("0")
		_0,
		@SerializedName("90")
		_90,
		@SerializedName("180")
		_180,
		@SerializedName("270")
		_270;

		public Rotation cw(){
			return switch(this){
				case _0 -> _90;
				case _90 -> _180;
				case _180 -> _270;
				case _270 -> _0;
			};
		}

		public Rotation ccw(){
			return switch(this){
				case _0 -> _270;
				case _90 -> _0;
				case _180 -> _90;
				case _270 -> _180;
			};
		}

		public int value(){
			return switch(this){
				case _0 -> 0;
				case _90 -> 90;
				case _180 -> 180;
				case _270 -> 270;
			};
		}

		public static Rotation valueOf(int rotation){
			return switch(rotation){
				case 0 -> _0;
				case 90 -> _90;
				case 180 -> _180;
				case 270 -> _270;
				default -> throw new IllegalArgumentException("Unexpected value: " + rotation);
			};
		}
	}
}
