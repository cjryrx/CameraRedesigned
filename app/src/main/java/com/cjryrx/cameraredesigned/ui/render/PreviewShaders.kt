package com.cjryrx.cameraredesigned.ui.render

internal object PreviewShaders {
    val vertex = """#version 300 es
        layout(location=0) in vec2 aPosition;
        out vec2 uv;
        void main() { gl_Position=vec4(aPosition,0.,1.); uv=aPosition*.5+.5; }
    """.trimIndent()
    val external = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;
        uniform samplerExternalOES image;
        uniform mat4 textureMatrix;
        uniform int rotation;
        uniform bool mirror;
        in vec2 uv; out vec4 color;
        void main() {
            vec2 p=vec2(uv.x,1.-uv.y);
            if(mirror) p.x=1.-p.x;
            if(rotation==90) p=vec2(p.y,1.-p.x);
            else if(rotation==180) p=1.-p;
            else if(rotation==270) p=vec2(1.-p.y,p.x);
            color=texture(image,(textureMatrix*vec4(p.x,1.-p.y,0.,1.)).xy);
        }
    """.trimIndent()
    val copy = """#version 300 es
        precision highp float;
        uniform sampler2D image;
        in vec2 uv; out vec4 color;
        void main() { color=texture(image,uv); }
    """.trimIndent()
    val grade = """#version 300 es
        precision highp float;
        precision highp sampler3D;
        uniform sampler2D image;
        uniform sampler3D lut;
        uniform bool hasLut;
        uniform float lutSize;
        uniform vec3 domainMin,domainMax;
        uniform float strength,exposure,temperature,tint,saturation,vibrance,contrast,highlights,shadows;
        in vec2 uv; out vec4 color;
        vec3 linearize(vec3 c) { return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c)); }
        vec3 srgb(vec3 c) { return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c)); }
        float luma(vec3 c) { return dot(c,vec3(.2126,.7152,.0722)); }
        void main() {
            vec3 base=texture(image,uv).rgb; vec3 c;
            if(hasLut) {
                vec3 pos=clamp((base-domainMin)/(domainMax-domainMin),0.,1.);
                c=texture(lut,(pos*(lutSize-1.)+.5)/lutSize).rgb;
            } else {
                c=linearize(base)*exp2(exposure)*vec3(1.+temperature*.22+tint*.075,1.-tint*.15,1.-temperature*.22+tint*.075);
                float l=clamp(luma(c),0.,1.);
                c=srgb(max(c+shadows*pow(1.-l,3.)*.32+highlights*pow(l,3.)*.35,0.));
                c=(c-.5)*exp2(contrast)+.5;
                float gray=luma(c);
                float chroma=max(c.r,max(c.g,c.b))-min(c.r,min(c.g,c.b));
                c=vec3(gray)+(c-gray)*(1.+saturation)*(1.+vibrance*(1.-clamp(chroma,0.,1.)));
            }
            color=vec4(clamp(mix(base,c,strength),0.,1.),1.);
        }
    """.trimIndent()
    val blur = """#version 300 es
        precision highp float;
        uniform sampler2D image; uniform vec2 direction; uniform int radius;
        in vec2 uv; out vec4 color;
        void main() {
            vec3 sum=vec3(0.);
            for(int i=-12;i<=12;i++) if(abs(i)<=radius) sum+=texture(image,uv+float(i)*direction).rgb;
            color=vec4(sum/float(2*radius+1),1.);
        }
    """.trimIndent()
    val sharpen = """#version 300 es
        precision highp float;
        uniform sampler2D image,softened; uniform float amount;
        in vec2 uv; out vec4 color;
        void main() {
            vec3 base=texture(image,uv).rgb;
            vec3 delta=base-texture(softened,uv).rgb;
            color=vec4(clamp(base+delta*amount*step(vec3(2./255.),abs(delta)),0.,1.),1.);
        }
    """.trimIndent()
    val display = """#version 300 es
        precision highp float;
        uniform sampler2D image; uniform vec2 texel,center;
        uniform float zoom; uniform bool warnings,peaking;
        in vec2 uv; out vec4 color;
        float luma(vec3 c) { return dot(c,vec3(.2126,.7152,.0722)); }
        void main() {
            vec2 p=center+(uv-.5)/zoom;
            vec3 c=texture(image,p).rgb; float l=luma(c);
            if(warnings && mod(gl_FragCoord.x+gl_FragCoord.y,12.)<4.) {
                if(l>=250./255.) c=vec3(1.,.314,.314);
                if(l<=5./255.) c=vec3(.314,.50,1.);
            }
            if(peaking) {
                float edge=abs(4.*l-luma(texture(image,p-vec2(texel.x,0.)).rgb)-luma(texture(image,p+vec2(texel.x,0.)).rgb)
                    -luma(texture(image,p-vec2(0.,texel.y)).rgb)-luma(texture(image,p+vec2(0.,texel.y)).rgb));
                if(edge>100./255.) c=vec3(.55,1.,.38);
            }
            color=vec4(c,1.);
        }
    """.trimIndent()
}
