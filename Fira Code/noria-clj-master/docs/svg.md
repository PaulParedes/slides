## SVG rendering libraries 

### Batik
https://xmlgraphics.apache.org/batik/
* about 12Mb jar file
* depends on AWT

### Resvg
https://github.com/RazrFalcon/resvg
* Support different backends (Skia, Qt)
* Blazingly fast

### Gnome enterprise grade SVG renderer
https://github.com/GNOME/librsvg
* Depends on Cario

### NSvg
https://github.com/nickbrowne/nsvg
* Zero dependency
* CPU rendering
* SVG spec is partially supported

## Related libraries

### Lyon
https://github.com/nical/lyon
* Path tessellation library
* Runs on GPU
* No anti-aliasing support
* Used by [azul](https://github.com/maps4print/azul/blob/master/azul-widgets/svg.rs)
* Related [articles](https://nical.github.io/)

### Pathfinder
https://github.com/servo/pathfinder
* GPU based rasterizer
* General purpose
* Can be used for SVG rendering with some svg loader/parser
* Support anti-aliasing