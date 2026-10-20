#include "Renderer.h"

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <vector>

#define LOG_TAG "OpenBarnyard"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace ob {
namespace {

constexpr float kPi = 3.14159265358979323846f;

float ClampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
float Lerp(float a, float b, float t) { return a + (b - a) * t; }

// Shortest-arc angle interpolation for camera auto-alignment.
float LerpAngle(float a, float b, float t) {
    float d = std::fmod(b - a, 2.0f * kPi);
    if (d > kPi) d -= 2.0f * kPi;
    if (d < -kPi) d += 2.0f * kPi;
    return a + d * t;
}

struct Vertex {
    float pos[3];
    float normal[3];
    float color[3];
};

Vec3 TransformDir(const Mat4& m, const Vec3& d) {
    Vec3 r{m.m[0] * d.x + m.m[4] * d.y + m.m[8] * d.z,
           m.m[1] * d.x + m.m[5] * d.y + m.m[9] * d.z,
           m.m[2] * d.x + m.m[6] * d.y + m.m[10] * d.z};
    return r.Normalized();
}

// CPU geometry baker: appends transformed primitives into one vertex soup.
struct GeoBuilder {
    std::vector<Vertex> verts;

    void Push(const Mat4& m, const Vec3& p, const Vec3& n, float r, float g, float b) {
        const Vec3 wp = m.TransformPoint(p);
        const Vec3 wn = TransformDir(m, n);
        Vertex v{{wp.x, wp.y, wp.z}, {wn.x, wn.y, wn.z}, {r, g, b}};
        verts.push_back(v);
    }

    // Unit cube centred at origin (size 1), transformed by m.
    void Box(const Mat4& m, float r, float g, float b) {
        static const float kFaces[6][4][3] = {
            // +X, -X, +Y, -Y, +Z, -Z (corners CCW seen from outside)
            {{ .5f,-.5f,-.5f},{ .5f,-.5f, .5f},{ .5f, .5f, .5f},{ .5f, .5f,-.5f}},
            {{-.5f,-.5f, .5f},{-.5f,-.5f,-.5f},{-.5f, .5f,-.5f},{-.5f, .5f, .5f}},
            {{-.5f, .5f,-.5f},{ .5f, .5f,-.5f},{ .5f, .5f, .5f},{-.5f, .5f, .5f}},
            {{-.5f,-.5f, .5f},{ .5f,-.5f, .5f},{ .5f,-.5f,-.5f},{-.5f,-.5f,-.5f}},
            {{-.5f,-.5f, .5f},{ .5f,-.5f, .5f},{ .5f, .5f, .5f},{-.5f, .5f, .5f}},
            {{ .5f,-.5f,-.5f},{-.5f,-.5f,-.5f},{-.5f, .5f,-.5f},{ .5f, .5f,-.5f}},
        };
        static const float kNormals[6][3] = {
            {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
        };
        for (int f = 0; f < 6; ++f) {
            const Vec3 n{kNormals[f][0], kNormals[f][1], kNormals[f][2]};
            const int idx[6] = {0, 1, 2, 0, 2, 3};
            for (int i = 0; i < 6; ++i) {
                const float* c = kFaces[f][idx[i]];
                Push(m, Vec3{c[0], c[1], c[2]}, n, r, g, b);
            }
        }
    }

    // Convenience: axis-aligned box at (x,y,z) with size (sx,sy,sz), yaw rot.
    void BoxAt(float x, float y, float z, float sx, float sy, float sz,
               float r, float g, float b, float yaw = 0.0f) {
        const Mat4 m = Mat4::Translation(x, y, z) * Mat4::RotationY(yaw) * Mat4::Scaling(sx, sy, sz);
        Box(m, r, g, b);
    }

    // Open-ended prism (roof): triangular cross-section along Z.
    void Roof(const Mat4& m, float r, float g, float b) {
        // Cross-section in XY: (-.5,-.5) (.5,-.5) (0,.5); extruded z=-.5..5
        const Vec3 a{-0.5f, -0.5f, -0.5f}, bb{0.5f, -0.5f, -0.5f}, c{0.0f, 0.5f, -0.5f};
        const Vec3 d{-0.5f, -0.5f,  0.5f}, e{0.5f, -0.5f,  0.5f}, f{0.0f, 0.5f,  0.5f};
        const Vec3 nLeft  = Vec3{-0.7071f, 0.7071f, 0.0f};
        const Vec3 nRight = Vec3{ 0.7071f, 0.7071f, 0.0f};
        const Vec3 nBack  = Vec3{0.0f, 0.0f, -1.0f};
        const Vec3 nFront = Vec3{0.0f, 0.0f,  1.0f};
        auto tri = [&](const Vec3& p1, const Vec3& p2, const Vec3& p3, const Vec3& n) {
            Push(m, p1, n, r, g, b); Push(m, p2, n, r, g, b); Push(m, p3, n, r, g, b);
        };
        auto quad = [&](const Vec3& p1, const Vec3& p2, const Vec3& p3, const Vec3& p4, const Vec3& n) {
            tri(p1, p2, p3, n); tri(p1, p3, p4, n);
        };
        quad(a, d, f, c, nLeft);     // left slope
        quad(bb, c, f, e, nRight);   // right slope
        tri(a, c, bb, nBack);        // back gable
        tri(d, e, f, nFront);        // front gable
    }

    // Cylinder / cone along Y, height 1 centred at origin before transform.
    void Cylinder(const Mat4& m, float rTop, float rBottom, int segments,
                  float r, float g, float b) {
        for (int i = 0; i < segments; ++i) {
            const float a0 = (2.0f * kPi * i) / segments;
            const float a1 = (2.0f * kPi * (i + 1)) / segments;
            const Vec3 n0{std::cos(a0), 0.0f, std::sin(a0)};
            const Vec3 n1{std::cos(a1), 0.0f, std::sin(a1)};
            const Vec3 b0{n0.x * rBottom, -0.5f, n0.z * rBottom};
            const Vec3 b1{n1.x * rBottom, -0.5f, n1.z * rBottom};
            const Vec3 t0{n0.x * rTop, 0.5f, n0.z * rTop};
            const Vec3 t1{n1.x * rTop, 0.5f, n1.z * rTop};
            Push(m, b0, n0, r, g, b); Push(m, b1, n1, r, g, b); Push(m, t1, n1, r, g, b);
            Push(m, b0, n0, r, g, b); Push(m, t1, n1, r, g, b); Push(m, t0, n0, r, g, b);
        }
    }

    // Flat quad on XZ plane (normal +Y), size 1 before transform.
    void GroundQuad(const Mat4& m, float r, float g, float b) {
        const Vec3 n{0.0f, 1.0f, 0.0f};
        Push(m, Vec3{-0.5f, 0, -0.5f}, n, r, g, b);
        Push(m, Vec3{-0.5f, 0,  0.5f}, n, r, g, b);
        Push(m, Vec3{ 0.5f, 0,  0.5f}, n, r, g, b);
        Push(m, Vec3{-0.5f, 0, -0.5f}, n, r, g, b);
        Push(m, Vec3{ 0.5f, 0,  0.5f}, n, r, g, b);
        Push(m, Vec3{ 0.5f, 0, -0.5f}, n, r, g, b);
    }
};

const char* kVertShader =
    "attribute vec3 aPos;\n"
    "attribute vec3 aNormal;\n"
    "attribute vec3 aColor;\n"
    "uniform mat4 uMvp;\n"
    "uniform mat4 uModel;\n"
    "uniform mat3 uNormalMat;\n"
    "uniform mat4 uView;\n"
    "varying vec3 vColor;\n"
    "varying vec3 vNormal;\n"
    "varying float vViewZ;\n"
    "void main() {\n"
    "  vec4 world = uModel * vec4(aPos, 1.0);\n"
    "  vec4 viewPos = uView * world;\n"
    "  vViewZ = -viewPos.z;\n"
    "  vNormal = normalize(uNormalMat * aNormal);\n"
    "  vColor = aColor;\n"
    "  gl_Position = uMvp * vec4(aPos, 1.0);\n"
    "}\n";

const char* kFragShader =
    "precision mediump float;\n"
    "varying vec3 vColor;\n"
    "varying vec3 vNormal;\n"
    "varying float vViewZ;\n"
    "uniform vec4 uColor;\n"
    "uniform vec3 uLightDir;\n"
    "uniform vec3 uFogColor;\n"
    "uniform float uFogDensity;\n"
    "void main() {\n"
    "  vec3 n = normalize(vNormal);\n"
    "  float diff = max(dot(n, normalize(uLightDir)), 0.0);\n"
    "  float light = 0.38 + 0.70 * diff;\n"
    "  vec3 col = vColor * uColor.rgb * light;\n"
    "  float fog = 1.0 - exp(-uFogDensity * uFogDensity * vViewZ * vViewZ);\n"
    "  col = mix(col, uFogColor, clamp(fog, 0.0, 1.0));\n"
    "  gl_FragColor = vec4(col, uColor.a);\n"
    "}\n";

const char* kBlitVert =
    "attribute vec2 aPos;\n"
    "attribute vec2 aUv;\n"
    "varying vec2 vUv;\n"
    "void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

const char* kBlitFrag =
    "precision mediump float;\n"
    "varying vec2 vUv;\n"
    "uniform sampler2D uTex;\n"
    "void main() { gl_FragColor = texture2D(uTex, vUv); }\n";

GLuint CompileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetShaderInfoLog(sh, sizeof(log), nullptr, log);
        LOGE("Shader compile failed: %s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

GLuint LinkProgram(const char* vs, const char* fs) {
    const GLuint v = CompileShader(GL_VERTEX_SHADER, vs);
    const GLuint f = CompileShader(GL_FRAGMENT_SHADER, fs);
    if (!v || !f) { if (v) glDeleteShader(v); if (f) glDeleteShader(f); return 0; }
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        LOGE("Program link failed: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

} // namespace

Renderer::~Renderer() { Shutdown(); }

GLuint Renderer::MakeBuffer(const void* data, size_t bytes) {
    GLuint buf = 0;
    glGenBuffers(1, &buf);
    glBindBuffer(GL_ARRAY_BUFFER, buf);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(bytes), data, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return buf;
}

bool Renderer::Initialize(ANativeWindow* window, const EngineConfig& cfg) {
    if (window == nullptr) return false;
    Shutdown();

    m_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (m_display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return false; }
    if (!eglInitialize(m_display, nullptr, nullptr)) { LOGE("eglInitialize failed"); Shutdown(); return false; }

    // MSAA via the window surface only makes sense when rendering directly
    // at full resolution; the scaled path renders offscreen (see EnsureRenderTarget).
    const bool directMsaa = cfg.msaa > 0 && !cfg.dynamicResolution && cfg.resolutionScale >= 0.999f;
    const int wantSamples = directMsaa ? cfg.msaa : 0;

    EGLConfig chosen = nullptr;
    for (int samples = wantSamples; samples >= 0; samples = (samples > 2 ? 2 : 0)) {
        const EGLint attrs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT | EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24,
            EGL_SAMPLE_BUFFERS, samples > 0 ? 1 : 0,
            EGL_SAMPLES, samples,
            EGL_NONE
        };
        EGLint num = 0;
        if (eglChooseConfig(m_display, attrs, &chosen, 1, &num) && num > 0) break;
        chosen = nullptr;
        if (samples == 0) break;
    }
    if (chosen == nullptr) { LOGE("eglChooseConfig failed"); Shutdown(); return false; }
    m_eglConfig = chosen;

    EGLint format = 0;
    eglGetConfigAttrib(m_display, chosen, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(window, 0, 0, format);

    // Try ES3 first (better drivers/perf on modern SoCs), fall back to ES2
    // so decade-old 32-bit phones still run.
    m_context = EGL_NO_CONTEXT;
    const EGLint ctx3[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    m_context = eglCreateContext(m_display, chosen, EGL_NO_CONTEXT, ctx3);
    m_glVersion = 3;
    if (m_context == EGL_NO_CONTEXT) {
        const EGLint ctx2[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        m_context = eglCreateContext(m_display, chosen, EGL_NO_CONTEXT, ctx2);
        m_glVersion = 2;
    }
    if (m_context == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed"); Shutdown(); return false; }

    m_surface = eglCreateWindowSurface(m_display, chosen, window, nullptr);
    if (m_surface == EGL_NO_SURFACE) { LOGE("eglCreateWindowSurface failed"); Shutdown(); return false; }
    if (!eglMakeCurrent(m_display, m_surface, m_surface, m_context)) {
        LOGE("eglMakeCurrent failed");
        Shutdown();
        return false;
    }
    eglSwapInterval(m_display, cfg.vsync ? 1 : 0);
    eglQuerySurface(m_display, m_surface, EGL_WIDTH, &m_surfW);
    eglQuerySurface(m_display, m_surface, EGL_HEIGHT, &m_surfH);

    const char* vendor   = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* version  = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    char info[512];
    std::snprintf(info, sizeof(info), "GLES%d · %s · %s · %s · %dx%d",
                  m_glVersion, vendor ? vendor : "?", renderer ? renderer : "?",
                  version ? version : "?", m_surfW, m_surfH);
    m_glInfo = info;
    LOGI("Renderer up: %s", info);

    glEnable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE); // baked roof/fence geometry is single-sided-safe either way
    glClearColor(m_skyR, m_skyG, m_skyB, 1.0f);

    if (!CreatePrograms()) { Shutdown(); return false; }
    BuildSceneGeometry();
    return true;
}

bool Renderer::CreatePrograms() {
    m_program = LinkProgram(kVertShader, kFragShader);
    if (!m_program) return false;
    m_locMvp        = glGetUniformLocation(m_program, "uMvp");
    m_locModel      = glGetUniformLocation(m_program, "uModel");
    m_locNormalMat  = glGetUniformLocation(m_program, "uNormalMat");
    m_locView       = glGetUniformLocation(m_program, "uView");
    m_locColor      = glGetUniformLocation(m_program, "uColor");
    m_locLightDir   = glGetUniformLocation(m_program, "uLightDir");
    m_locFogColor   = glGetUniformLocation(m_program, "uFogColor");
    m_locFogDensity = glGetUniformLocation(m_program, "uFogDensity");
    m_attrPos       = glGetAttribLocation(m_program, "aPos");
    m_attrNormal    = glGetAttribLocation(m_program, "aNormal");
    m_attrColor     = glGetAttribLocation(m_program, "aColor");

    m_blitProgram = LinkProgram(kBlitVert, kBlitFrag);
    if (!m_blitProgram) return false;
    m_blitAttrPos = glGetAttribLocation(m_blitProgram, "aPos");
    m_blitAttrUv  = glGetAttribLocation(m_blitProgram, "aUv");
    m_blitLocTex  = glGetUniformLocation(m_blitProgram, "uTex");

    // Fullscreen triangle strip (pos.xy, uv.xy)
    const float blitVerts[] = {
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f,
    };
    m_blitVbo = MakeBuffer(blitVerts, sizeof(blitVerts));
    return m_program != 0;
}

void Renderer::BuildSceneGeometry() {
    // ---- Ground: checkerboard pasture ----------------------------------
    {
        GeoBuilder g;
        const int cells = 24;
        const float world = 120.0f;
        const float cell = world / cells;
        for (int i = 0; i < cells; ++i) {
            for (int j = 0; j < cells; ++j) {
                const bool alt = ((i + j) & 1) == 0;
                const float r = alt ? 0.30f : 0.36f;
                const float gg = alt ? 0.62f : 0.70f;
                const float b = alt ? 0.25f : 0.28f;
                const float x = -world * 0.5f + cell * (i + 0.5f);
                const float z = -world * 0.5f + cell * (j + 0.5f);
                g.GroundQuad(Mat4::Translation(x, 0, z) * Mat4::Scaling(cell, 1, cell), r, gg, b);
            }
        }
        // Dirt path to the barn
        g.GroundQuad(Mat4::Translation(-7, 0.01f, -6) * Mat4::Scaling(4, 1, 26), 0.62f, 0.50f, 0.34f);
        m_groundVerts = static_cast<int>(g.verts.size());
        m_groundVbo = MakeBuffer(g.verts.data(), g.verts.size() * sizeof(Vertex));
    }

    // ---- Static props: barn, silo, fences, trees, windmill tower --------
    {
        GeoBuilder g;
        // Barn (red), front facing +Z at (-14,-18)
        g.BoxAt(-14, 2.5f, -18, 10, 5, 7, 0.72f, 0.20f, 0.16f);
        {
            const Mat4 roofM = Mat4::Translation(-14, 6.2f, -18) * Mat4::Scaling(11.5f, 2.6f, 8.0f);
            g.Roof(roofM, 0.35f, 0.18f, 0.12f);
        }
        g.BoxAt(-14, 1.6f, -14.45f, 2.6f, 3.2f, 0.15f, 0.92f, 0.90f, 0.86f);   // door
        g.BoxAt(-14, 1.6f, -14.35f, 2.8f, 0.28f, 0.1f, 0.55f, 0.14f, 0.11f);   // door cross (h)
        g.BoxAt(-14, 1.6f, -14.35f, 0.28f, 3.3f, 0.1f, 0.55f, 0.14f, 0.11f);   // door cross (v)
        g.BoxAt(-17.5f, 3.4f, -14.45f, 1.2f, 1.2f, 0.12f, 0.85f, 0.88f, 0.70f); // window L
        g.BoxAt(-10.5f, 3.4f, -14.45f, 1.2f, 1.2f, 0.12f, 0.85f, 0.88f, 0.70f); // window R

        // Silo next to the barn
        {
            const Mat4 silo = Mat4::Translation(-6.5f, 4.5f, -19) * Mat4::Scaling(1, 9, 1);
            g.Cylinder(silo, 1.6f, 1.6f, 14, 0.75f, 0.77f, 0.80f);
            const Mat4 cap = Mat4::Translation(-6.5f, 9.7f, -19) * Mat4::Scaling(1, 1.6f, 1);
            g.Cylinder(cap, 0.05f, 1.65f, 14, 0.55f, 0.30f, 0.20f);
        }

        // Second small shed
        g.BoxAt(16, 1.4f, -10, 5, 2.8f, 4, 0.60f, 0.42f, 0.24f, 0.4f);
        {
            const Mat4 roofM = Mat4::Translation(16, 3.3f, -10) * Mat4::RotationY(0.4f) * Mat4::Scaling(5.8f, 1.2f, 4.6f);
            g.Roof(roofM, 0.30f, 0.22f, 0.14f);
        }

        // Perimeter fence (square, half-extent 46)
        const float H = 46.0f;
        for (float t = -H; t <= H; t += 4.0f) {
            g.BoxAt(t, 0.65f, -H, 0.22f, 1.3f, 0.22f, 0.55f, 0.40f, 0.22f);
            g.BoxAt(t, 0.65f,  H, 0.22f, 1.3f, 0.22f, 0.55f, 0.40f, 0.22f);
            g.BoxAt(-H, 0.65f, t, 0.22f, 1.3f, 0.22f, 0.55f, 0.40f, 0.22f);
            g.BoxAt( H, 0.65f, t, 0.22f, 1.3f, 0.22f, 0.55f, 0.40f, 0.22f);
        }
        g.BoxAt(0, 1.05f, -H, 2 * H, 0.14f, 0.1f, 0.62f, 0.46f, 0.26f);
        g.BoxAt(0, 0.55f, -H, 2 * H, 0.14f, 0.1f, 0.62f, 0.46f, 0.26f);
        g.BoxAt(0, 1.05f,  H, 2 * H, 0.14f, 0.1f, 0.62f, 0.46f, 0.26f);
        g.BoxAt(0, 0.55f,  H, 2 * H, 0.14f, 0.1f, 0.62f, 0.46f, 0.26f);
        g.BoxAt(-H, 1.05f, 0, 0.1f, 0.14f, 2 * H, 0.62f, 0.46f, 0.26f);
        g.BoxAt(-H, 0.55f, 0, 0.1f, 0.14f, 2 * H, 0.62f, 0.46f, 0.26f);
        g.BoxAt( H, 1.05f, 0, 0.1f, 0.14f, 2 * H, 0.62f, 0.46f, 0.26f);
        g.BoxAt( H, 0.55f, 0, 0.1f, 0.14f, 2 * H, 0.62f, 0.46f, 0.26f);

        // Trees: trunk + two foliage cones
        const float trees[][2] = {{10, 8}, {-24, 4}, {24, 18}, {-8, -30}, {30, -26}, {-32, -12}, {6, 26}, {-20, 24}};
        for (const auto& t : trees) {
            const Mat4 trunk = Mat4::Translation(t[0], 1.2f, t[1]) * Mat4::Scaling(1, 2.4f, 1);
            g.Cylinder(trunk, 0.22f, 0.32f, 7, 0.42f, 0.28f, 0.16f);
            const Mat4 fol1 = Mat4::Translation(t[0], 3.6f, t[1]) * Mat4::Scaling(1, 2.6f, 1);
            g.Cylinder(fol1, 0.1f, 1.7f, 9, 0.20f, 0.48f, 0.20f);
            const Mat4 fol2 = Mat4::Translation(t[0], 5.2f, t[1]) * Mat4::Scaling(1, 2.0f, 1);
            g.Cylinder(fol2, 0.05f, 1.15f, 9, 0.24f, 0.54f, 0.22f);
        }

        // Hay bales (cylinders lying on their side)
        const float bales[][3] = {{-4, 10, 0.3f}, {12, -4, 1.2f}, {20, 12, 2.1f}, {-26, -22, 0.8f}};
        for (const auto& bale : bales) {
            const Mat4 m = Mat4::Translation(bale[0], 0.8f, bale[1]) *
                           Mat4::RotationY(bale[2]) * Mat4::RotationZ(kPi * 0.5f) *
                           Mat4::Scaling(1, 1.4f, 1);
            g.Cylinder(m, 0.8f, 0.8f, 12, 0.86f, 0.72f, 0.34f);
        }

        // Windmill tower at (22,-30); blades are dynamic (m_bladesVbo)
        g.BoxAt(22, 4.5f, -30, 0.5f, 9.0f, 0.5f, 0.45f, 0.47f, 0.50f);
        g.BoxAt(21.2f, 2.0f, -30.8f, 0.28f, 4.4f, 0.28f, 0.40f, 0.42f, 0.45f);
        g.BoxAt(22.8f, 2.0f, -30.8f, 0.28f, 4.4f, 0.28f, 0.40f, 0.42f, 0.45f);
        g.BoxAt(21.2f, 2.0f, -29.2f, 0.28f, 4.4f, 0.28f, 0.40f, 0.42f, 0.45f);
        g.BoxAt(22.8f, 2.0f, -29.2f, 0.28f, 4.4f, 0.28f, 0.40f, 0.42f, 0.45f);
        g.BoxAt(22, 9.2f, -30.9f, 1.6f, 0.9f, 0.12f, 0.65f, 0.25f, 0.18f); // tail vane

        // Well
        {
            const Mat4 m = Mat4::Translation(4, 0.55f, -8) * Mat4::Scaling(1, 1.1f, 1);
            g.Cylinder(m, 1.1f, 1.2f, 10, 0.55f, 0.55f, 0.58f);
        }
        g.BoxAt(3.1f, 1.7f, -8, 0.18f, 2.2f, 0.18f, 0.42f, 0.28f, 0.16f);
        g.BoxAt(4.9f, 1.7f, -8, 0.18f, 2.2f, 0.18f, 0.42f, 0.28f, 0.16f);
        {
            const Mat4 roofM = Mat4::Translation(4, 3.1f, -8) * Mat4::RotationY(kPi * 0.5f) * Mat4::Scaling(2.6f, 0.8f, 2.2f);
            g.Roof(roofM, 0.62f, 0.20f, 0.15f);
        }

        m_propsVerts = static_cast<int>(g.verts.size());
        m_propsVbo = MakeBuffer(g.verts.data(), g.verts.size() * sizeof(Vertex));
        LOGI("Scene baked: ground=%d props=%d vertices", m_groundVerts, m_propsVerts);
    }

    // ---- Dynamic pieces ---------------------------------------------------
    {
        GeoBuilder cube;
        cube.Box(Mat4::Identity(), 1.0f, 1.0f, 1.0f);
        m_cubeVerts = static_cast<int>(cube.verts.size());
        m_cubeVbo = MakeBuffer(cube.verts.data(), cube.verts.size() * sizeof(Vertex));

        GeoBuilder blades;
        for (int i = 0; i < 4; ++i) {
            const float a = i * kPi * 0.5f;
            const Mat4 m = Mat4::RotationZ(a) * Mat4::Translation(0, 1.5f, 0) * Mat4::Scaling(0.55f, 3.0f, 0.08f);
            blades.Box(m, 0.80f, 0.82f, 0.85f);
        }
        blades.Box(Mat4::Scaling(0.6f, 0.6f, 0.4f), 0.4f, 0.4f, 0.42f);
        m_bladesVerts = static_cast<int>(blades.verts.size());
        m_bladesVbo = MakeBuffer(blades.verts.data(), blades.verts.size() * sizeof(Vertex));

        GeoBuilder cloud;
        cloud.BoxAt(0, 0, 0, 6, 1.2f, 3.4f, 1, 1, 1);
        cloud.BoxAt(1.8f, 0.5f, 0.2f, 3.6f, 1.1f, 2.6f, 1, 1, 1);
        cloud.BoxAt(-2.0f, 0.4f, -0.2f, 3.0f, 1.0f, 2.4f, 0.97f, 0.98f, 1);
        m_cloudVerts = static_cast<int>(cloud.verts.size());
        m_cloudVbo = MakeBuffer(cloud.verts.data(), cloud.verts.size() * sizeof(Vertex));

        GeoBuilder quad;
        quad.GroundQuad(Mat4::Identity(), 0, 0, 0);
        m_quadVerts = static_cast<int>(quad.verts.size());
        m_quadVbo = MakeBuffer(quad.verts.data(), quad.verts.size() * sizeof(Vertex));
    }
}

void Renderer::DestroyRenderTarget() {
    if (m_fbo) glDeleteFramebuffers(1, &m_fbo), m_fbo = 0;
    if (m_fboTex) glDeleteTextures(1, &m_fboTex), m_fboTex = 0;
    if (m_fboDepth) glDeleteRenderbuffers(1, &m_fboDepth), m_fboDepth = 0;
    m_rtW = m_rtH = 0;
    m_usingFbo = false;
}

bool Renderer::EnsureRenderTarget(int w, int h, float scale, bool wantFbo) {
    if (!wantFbo) {
        if (m_usingFbo) DestroyRenderTarget();
        m_rtW = w; m_rtH = h;
        return true;
    }
    int tw = static_cast<int>(w * scale + 0.5f);
    int th = static_cast<int>(h * scale + 0.5f);
    if (tw < 16) tw = 16;
    if (th < 16) th = 16;
    if (m_usingFbo && std::abs(tw - m_rtW) <= 4 && std::abs(th - m_rtH) <= 4) return true;

    DestroyRenderTarget();
    glGenTextures(1, &m_fboTex);
    glBindTexture(GL_TEXTURE_2D, m_fboTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, tw, th, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    glGenRenderbuffers(1, &m_fboDepth);
    glBindRenderbuffer(GL_RENDERBUFFER, m_fboDepth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, tw, th);

    glGenFramebuffers(1, &m_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, m_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, m_fboTex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, m_fboDepth);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Offscreen FBO incomplete (0x%x); falling back to direct rendering", status);
        DestroyRenderTarget();
        m_rtW = w; m_rtH = h;
        return true; // direct path still works
    }
    m_rtW = tw; m_rtH = th;
    m_usingFbo = true;
    return true;
}

void Renderer::UpdateSimulation(float dt, InputState& input, const EngineConfig& cfg) {
    // Camera look
    float cdx = 0, cdy = 0;
    input.ConsumeCameraDelta(&cdx, &cdy);
    if (cdx != 0.0f || cdy != 0.0f) {
        const float sens = 0.0045f * cfg.cameraSensitivity;
        m_camYaw -= cdx * sens;
        m_camPitch += (cfg.invertCameraY ? -cdy : cdy) * sens;
        m_camPitch = ClampF(m_camPitch, 0.05f, 1.25f);
        m_sinceCamTouch = 0.0f;
    } else {
        m_sinceCamTouch += dt;
    }

    // Movement relative to camera yaw
    const float sx = input.StickX();
    const float sy = input.StickY(); // +1 = push up/forward (Java already flipped)
    const float mag = std::sqrt(sx * sx + sy * sy);
    const bool moving = mag > 0.12f;
    const float runMul = input.IsDown(kCmdSpeed) ? 1.75f : 1.0f;
    if (moving) {
        const float inv = 1.0f / (mag > 1.0f ? mag : 1.0f);
        const float fx = -std::sin(m_camYaw), fz = -std::cos(m_camYaw); // camera forward on XZ
        const float rx = -fz, rz = fx;                                  // camera right
        Vec3 dir{fx * sy * inv + rx * sx * inv, 0.0f, fz * sy * inv + rz * sx * inv};
        const float dl = dir.Length();
        if (dl > 0.0001f) {
            dir = dir * (1.0f / dl);
            const float speed = 7.0f * runMul * (mag > 1.0f ? 1.0f : mag);
            m_playerPos.x += dir.x * speed * dt;
            m_playerPos.z += dir.z * speed * dt;
            const float targetYaw = std::atan2(dir.x, dir.z);
            m_playerYaw = LerpAngle(m_playerYaw, targetYaw, ClampF(dt * 10.0f, 0.0f, 1.0f));
            if (m_sinceCamTouch > 2.5f) {
                m_camYaw = LerpAngle(m_camYaw, targetYaw + kPi, ClampF(dt * 1.2f, 0.0f, 1.0f));
            }
        }
        m_walkPhase += dt * (6.0f * runMul);
        m_moveBlend = std::min(1.0f, m_moveBlend + dt * 6.0f);
    } else {
        m_moveBlend = std::max(0.0f, m_moveBlend - dt * 6.0f);
    }

    // Keep the cow inside the fence
    m_playerPos.x = ClampF(m_playerPos.x, -44.0f, 44.0f);
    m_playerPos.z = ClampF(m_playerPos.z, -44.0f, 44.0f);

    // Jump / gravity
    const bool jump = input.IsDown(kCmdJump);
    if (jump && !m_prevJump && m_grounded) {
        m_velY = 8.5f;
        m_grounded = false;
    }
    m_prevJump = jump;
    if (!m_grounded) {
        m_velY -= 22.0f * dt;
        m_playerPos.y += m_velY * dt;
        if (m_playerPos.y <= 0.0f) {
            m_playerPos.y = 0.0f;
            m_velY = 0.0f;
            m_grounded = true;
        }
    }

    // Interact → happy head-nod ("moo")
    const bool interact = input.IsDown(kCmdInteract);
    if (interact && !m_prevInteract) m_nodTimer = 0.55f;
    m_prevInteract = interact;
    if (m_nodTimer > 0.0f) m_nodTimer -= dt;
}

void Renderer::DrawMesh(GLuint vbo, int vertexCount, const Mat4& model,
                        const Mat4& viewProj, const Mat4& view,
                        float r, float g, float b, float a) {
    if (!vbo || vertexCount <= 0) return;
    const Mat4 mvp = viewProj * model;
    glUniformMatrix4fv(m_locMvp, 1, GL_FALSE, mvp.m);
    glUniformMatrix4fv(m_locModel, 1, GL_FALSE, model.m);
    glUniformMatrix4fv(m_locView, 1, GL_FALSE, view.m);
    // Normal matrix = upper 3x3 of model (uniform-enough scaling in this scene)
    const float nm[9] = {
        model.m[0], model.m[1], model.m[2],
        model.m[4], model.m[5], model.m[6],
        model.m[8], model.m[9], model.m[10]
    };
    glUniformMatrix3fv(m_locNormalMat, 1, GL_FALSE, nm);
    glUniform4f(m_locColor, r, g, b, a);

    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    const GLsizei stride = static_cast<GLsizei>(sizeof(Vertex));
    glEnableVertexAttribArray(static_cast<GLuint>(m_attrPos));
    glVertexAttribPointer(static_cast<GLuint>(m_attrPos), 3, GL_FLOAT, GL_FALSE, stride,
                          reinterpret_cast<const void*>(0));
    glEnableVertexAttribArray(static_cast<GLuint>(m_attrNormal));
    glVertexAttribPointer(static_cast<GLuint>(m_attrNormal), 3, GL_FLOAT, GL_FALSE, stride,
                          reinterpret_cast<const void*>(sizeof(float) * 3));
    glEnableVertexAttribArray(static_cast<GLuint>(m_attrColor));
    glVertexAttribPointer(static_cast<GLuint>(m_attrColor), 3, GL_FLOAT, GL_FALSE, stride,
                          reinterpret_cast<const void*>(sizeof(float) * 6));
    glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    glDisableVertexAttribArray(static_cast<GLuint>(m_attrPos));
    glDisableVertexAttribArray(static_cast<GLuint>(m_attrNormal));
    glDisableVertexAttribArray(static_cast<GLuint>(m_attrColor));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void Renderer::DrawPlayer(const Mat4& viewProj, const Mat4& view) {
    const float bob = std::sin(m_walkPhase * 2.0f) * 0.05f * m_moveBlend;
    const Mat4 base = Mat4::Translation(m_playerPos.x, m_playerPos.y, m_playerPos.z) *
                      Mat4::RotationY(m_playerYaw);
    auto part = [&](const Mat4& local, float r, float g, float b) {
        DrawMesh(m_cubeVbo, m_cubeVerts, base * local, viewProj, view, r, g, b, 1.0f);
    };
    // Legs (pivot at hip, swing while walking)
    const float swing = std::sin(m_walkPhase * 2.0f) * 0.55f * m_moveBlend;
    const float legPos[4][2] = {{-0.28f, 0.42f}, {0.28f, 0.42f}, {-0.28f, -0.42f}, {0.28f, -0.42f}};
    for (int i = 0; i < 4; ++i) {
        const float phase = (i == 0 || i == 3) ? swing : -swing;
        const Mat4 leg = Mat4::Translation(legPos[i][0], 0.72f, legPos[i][1]) *
                         Mat4::RotationX(phase) *
                         Mat4::Translation(0, -0.36f, 0) * Mat4::Scaling(0.20f, 0.72f, 0.20f);
        part(leg, 0.93f, 0.92f, 0.90f);
    }
    // Body + spots
    part(Mat4::Translation(0, 1.05f + bob, 0) * Mat4::Scaling(0.85f, 0.72f, 1.30f), 0.96f, 0.95f, 0.93f);
    part(Mat4::Translation(0.30f, 1.22f + bob, 0.18f) * Mat4::Scaling(0.28f, 0.30f, 0.42f), 0.16f, 0.15f, 0.16f);
    part(Mat4::Translation(-0.28f, 0.95f + bob, -0.30f) * Mat4::Scaling(0.26f, 0.26f, 0.36f), 0.16f, 0.15f, 0.16f);
    // Head (nods on Interact)
    const float nod = m_nodTimer > 0.0f ? std::sin(m_nodTimer * 18.0f) * 0.22f * (m_nodTimer / 0.55f) : 0.0f;
    const Mat4 headBase = Mat4::Translation(0, 1.32f + bob, 0.78f) * Mat4::RotationX(nod);
    part(headBase * Mat4::Translation(0, 0, 0.10f) * Mat4::Scaling(0.55f, 0.55f, 0.60f), 0.96f, 0.95f, 0.93f);
    part(headBase * Mat4::Translation(0, -0.10f, 0.44f) * Mat4::Scaling(0.42f, 0.32f, 0.18f), 0.92f, 0.62f, 0.62f); // snout
    part(headBase * Mat4::Translation(0.36f, 0.12f, 0.02f) * Mat4::Scaling(0.18f, 0.10f, 0.22f), 0.90f, 0.88f, 0.86f); // ear R
    part(headBase * Mat4::Translation(-0.36f, 0.12f, 0.02f) * Mat4::Scaling(0.18f, 0.10f, 0.22f), 0.90f, 0.88f, 0.86f); // ear L
    part(headBase * Mat4::Translation(0.18f, 0.34f, 0.0f) * Mat4::Scaling(0.08f, 0.20f, 0.08f), 0.85f, 0.80f, 0.66f); // horn R
    part(headBase * Mat4::Translation(-0.18f, 0.34f, 0.0f) * Mat4::Scaling(0.08f, 0.20f, 0.08f), 0.85f, 0.80f, 0.66f); // horn L
    // Eyes
    part(headBase * Mat4::Translation(0.15f, 0.08f, 0.41f) * Mat4::Scaling(0.07f, 0.09f, 0.03f), 0.08f, 0.08f, 0.10f);
    part(headBase * Mat4::Translation(-0.15f, 0.08f, 0.41f) * Mat4::Scaling(0.07f, 0.09f, 0.03f), 0.08f, 0.08f, 0.10f);
    // Tail
    const float wag = std::sin(m_walkPhase * 1.4f + 1.0f) * 0.3f;
    part(Mat4::Translation(0, 1.30f, -0.66f) * Mat4::RotationZ(wag) *
         Mat4::Translation(0, -0.32f, 0) * Mat4::Scaling(0.09f, 0.64f, 0.09f), 0.90f, 0.88f, 0.85f);

    // Blob shadow (cheap; quality settings only change softness we fake by alpha)
    if (m_quadVbo) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(GL_FALSE);
        const float hFade = ClampF(1.0f - m_playerPos.y * 0.10f, 0.25f, 1.0f);
        const Mat4 shadow = Mat4::Translation(m_playerPos.x, 0.02f, m_playerPos.z) * Mat4::Scaling(1.7f, 1, 2.2f);
        DrawMesh(m_quadVbo, m_quadVerts, shadow, viewProj, view, 0.0f, 0.0f, 0.0f, 0.32f * hFade);
        glDepthMask(GL_TRUE);
        glDisable(GL_BLEND);
    }
}

void Renderer::DrawScene(const Mat4& viewProj, const Mat4& view, float elapsed) {
    glUseProgram(m_program);
    glUniform3f(m_locLightDir, m_lightDir.x, m_lightDir.y, m_lightDir.z);
    glUniform3f(m_locFogColor, m_skyR, m_skyG, m_skyB);
    glUniform1f(m_locFogDensity, 0.0075f);

    const Mat4 identity = Mat4::Identity();
    DrawMesh(m_groundVbo, m_groundVerts, identity, viewProj, view, 1, 1, 1, 1);
    DrawMesh(m_propsVbo, m_propsVerts, identity, viewProj, view, 1, 1, 1, 1);

    // Windmill blades
    const Mat4 blades = Mat4::Translation(22, 9.2f, -29.2f) * Mat4::RotationY(kPi) *
                        Mat4::RotationZ(elapsed * 0.9f);
    DrawMesh(m_bladesVbo, m_bladesVerts, blades, viewProj, view, 1, 1, 1, 1);

    // Drifting clouds
    for (int i = 0; i < 4; ++i) {
        const float cx = std::fmod(elapsed * (0.7f + 0.23f * i) + i * 37.0f, 160.0f) - 80.0f;
        const float cz = -30.0f + i * 18.0f;
        const float cy = 26.0f + (i % 3) * 2.0f;
        DrawMesh(m_cloudVbo, m_cloudVerts, Mat4::Translation(cx, cy, cz), viewProj, view,
                 1.05f, 1.05f, 1.08f, 1.0f);
    }

    DrawPlayer(viewProj, view);
}

bool Renderer::DrawFrame(float dtSeconds, float elapsedSeconds, InputState& input,
                         const EngineConfig& cfg, float renderScale) {
    if (!IsInitialized()) return false;

    int w = 0, h = 0;
    eglQuerySurface(m_display, m_surface, EGL_WIDTH, &w);
    eglQuerySurface(m_display, m_surface, EGL_HEIGHT, &h);
    if (w <= 0 || h <= 0) return true; // transient (rotation), skip frame
    m_surfW = w; m_surfH = h;

    UpdateSimulation(dtSeconds, input, cfg);

    // Day cycle: 240 s loop, light + sky drift gently between palettes.
    const float dayT = std::fmod(elapsedSeconds, 240.0f) / 240.0f; // 0..1
    const float sunA = dayT * 2.0f * kPi;
    m_lightDir = Vec3{std::cos(sunA) * 0.6f, 0.55f + 0.45f * std::sin(sunA + 1.2f), 0.35f}.Normalized();
    const float warmth = 0.5f + 0.5f * std::cos(sunA * 2.0f); // two "golden hours" per loop
    m_skyR = Lerp(0.42f, 0.88f, warmth * 0.55f);
    m_skyG = Lerp(0.66f, 0.72f, warmth * 0.35f);
    m_skyB = Lerp(0.96f, 0.62f, warmth * 0.55f);

    const bool wantFbo = renderScale < 0.999f || cfg.dynamicResolution;
    EnsureRenderTarget(w, h, renderScale, wantFbo);
    const int rtW = m_usingFbo ? m_rtW : w;
    const int rtH = m_usingFbo ? m_rtH : h;

    // Camera
    const Vec3 target{m_playerPos.x, m_playerPos.y + 1.6f, m_playerPos.z};
    const float dist = 9.0f;
    const Vec3 eye{
        target.x + std::sin(m_camYaw) * std::cos(m_camPitch) * dist,
        target.y + std::sin(m_camPitch) * dist,
        target.z + std::cos(m_camYaw) * std::cos(m_camPitch) * dist
    };
    const Mat4 view = Mat4::LookAt(eye, target, Vec3{0, 1, 0});
    const float aspect = rtH > 0 ? static_cast<float>(rtW) / static_cast<float>(rtH) : 1.7f;
    const Mat4 proj = Mat4::Perspective(1.05f, aspect, 0.1f, 400.0f);
    const Mat4 viewProj = proj * view;

    if (m_usingFbo) glBindFramebuffer(GL_FRAMEBUFFER, m_fbo);
    glViewport(0, 0, rtW, rtH);
    glClearColor(m_skyR, m_skyG, m_skyB, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    DrawScene(viewProj, view, elapsedSeconds);

    if (m_usingFbo) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, w, h);
        glDisable(GL_DEPTH_TEST);
        glUseProgram(m_blitProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, m_fboTex);
        glUniform1i(m_blitLocTex, 0);
        glBindBuffer(GL_ARRAY_BUFFER, m_blitVbo);
        glEnableVertexAttribArray(static_cast<GLuint>(m_blitAttrPos));
        glVertexAttribPointer(static_cast<GLuint>(m_blitAttrPos), 2, GL_FLOAT, GL_FALSE, 16,
                              reinterpret_cast<const void*>(0));
        glEnableVertexAttribArray(static_cast<GLuint>(m_blitAttrUv));
        glVertexAttribPointer(static_cast<GLuint>(m_blitAttrUv), 2, GL_FLOAT, GL_FALSE, 16,
                              reinterpret_cast<const void*>(sizeof(float) * 2));
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(static_cast<GLuint>(m_blitAttrPos));
        glDisableVertexAttribArray(static_cast<GLuint>(m_blitAttrUv));
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glEnable(GL_DEPTH_TEST);
    }

    if (!eglSwapBuffers(m_display, m_surface)) {
        const EGLint err = eglGetError();
        LOGE("eglSwapBuffers failed: 0x%x", err);
        return err != EGL_BAD_SURFACE && err != EGL_BAD_NATIVE_WINDOW;
    }
    return true;
}

void Renderer::DestroyProgramsAndBuffers() {
    DestroyRenderTarget();
    auto delBuf = [](GLuint& b) { if (b) { glDeleteBuffers(1, &b); b = 0; } };
    delBuf(m_groundVbo); delBuf(m_propsVbo); delBuf(m_cubeVbo);
    delBuf(m_bladesVbo); delBuf(m_cloudVbo); delBuf(m_quadVbo); delBuf(m_blitVbo);
    if (m_program) { glDeleteProgram(m_program); m_program = 0; }
    if (m_blitProgram) { glDeleteProgram(m_blitProgram); m_blitProgram = 0; }
}

void Renderer::Shutdown() {
    if (m_display != EGL_NO_DISPLAY && m_context != EGL_NO_CONTEXT) {
        eglMakeCurrent(m_display, m_surface, m_surface, m_context);
        DestroyProgramsAndBuffers();
    }
    if (m_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (m_context != EGL_NO_CONTEXT) eglDestroyContext(m_display, m_context);
        if (m_surface != EGL_NO_SURFACE) eglDestroySurface(m_display, m_surface);
        eglTerminate(m_display);
    }
    m_context = EGL_NO_CONTEXT;
    m_surface = EGL_NO_SURFACE;
    m_display = EGL_NO_DISPLAY;
    m_eglConfig = nullptr;
    m_surfW = m_surfH = 0;
}

} // namespace ob
