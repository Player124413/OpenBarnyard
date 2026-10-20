#pragma once
// OpenBarnyard Android — EGL/OpenGL ES renderer for the Android platform
// layer. It renders a small procedural "farm" scene (pasture, barn, silo,
// windmill, a controllable cow) which exercises the whole Android pipeline
// end-to-end — surface lifecycle, dynamic resolution, frame pacing and the
// touch-command input path — while the TOSHI engine GL port is completed
// (see Android/PORTING.md for the engine integration plan). The scene is
// deliberately cheap: the static world is merged into ONE vertex buffer, so
// a frame is ~12 draw calls and runs on literally any GLES2 device.
#include <string>

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>

#include "../core/Config.h"
#include "../core/InputState.h"
#include "../core/Math3d.h"

namespace ob {

class Renderer {
public:
    Renderer() = default;
    ~Renderer();
    Renderer(const Renderer&) = delete;
    Renderer& operator=(const Renderer&) = delete;

    // Creates EGL context (ES3 preferred, ES2 fallback) on the calling
    // (render) thread. Returns false on failure; safe to retry with a new
    // window. The window reference is owned by the caller.
    bool Initialize(ANativeWindow* window, const EngineConfig& cfg);
    void Shutdown();
    bool IsInitialized() const { return m_context != EGL_NO_CONTEXT; }

    // Advances the simulation by dt and draws one frame at renderScale.
    // Returns false if the surface died (caller should Shutdown() and wait
    // for a fresh window).
    bool DrawFrame(float dtSeconds, float elapsedSeconds, InputState& input,
                   const EngineConfig& cfg, float renderScale);

    const std::string& GlInfo() const { return m_glInfo; }
    int SurfaceWidth() const { return m_surfW; }
    int SurfaceHeight() const { return m_surfH; }

private:
    bool CreatePrograms();
    void DestroyProgramsAndBuffers();
    bool EnsureRenderTarget(int w, int h, float scale, bool wantFbo);
    void DestroyRenderTarget();
    void BuildSceneGeometry();
    void DrawScene(const Mat4& viewProj, const Mat4& view, float elapsed);
    void DrawPlayer(const Mat4& viewProj, const Mat4& view);
    void DrawMesh(GLuint vbo, int vertexCount, const Mat4& model,
                  const Mat4& viewProj, const Mat4& view,
                  float r, float g, float b, float a);
    void UpdateSimulation(float dt, InputState& input, const EngineConfig& cfg);
    GLuint MakeBuffer(const void* data, size_t bytes);

    // EGL state
    EGLDisplay m_display = EGL_NO_DISPLAY;
    EGLSurface m_surface = EGL_NO_SURFACE;
    EGLContext m_context = EGL_NO_CONTEXT;
    EGLConfig  m_eglConfig = nullptr;
    int m_surfW = 0, m_surfH = 0;
    int m_glVersion = 2;
    std::string m_glInfo = "GL not initialised";

    // Programs
    GLuint m_program = 0;
    GLint  m_locMvp = -1, m_locModel = -1, m_locNormalMat = -1, m_locView = -1;
    GLint  m_locColor = -1, m_locLightDir = -1, m_locFogColor = -1, m_locFogDensity = -1;
    GLint  m_attrPos = -1, m_attrNormal = -1, m_attrColor = -1;
    GLuint m_blitProgram = 0;
    GLint  m_blitAttrPos = -1, m_blitAttrUv = -1, m_blitLocTex = -1;
    GLuint m_blitVbo = 0;

    // Geometry (vertex counts paired with buffers)
    GLuint m_groundVbo = 0;  int m_groundVerts = 0;
    GLuint m_propsVbo = 0;   int m_propsVerts = 0;
    GLuint m_cubeVbo = 0;    int m_cubeVerts = 0;
    GLuint m_bladesVbo = 0;  int m_bladesVerts = 0;
    GLuint m_cloudVbo = 0;   int m_cloudVerts = 0;
    GLuint m_quadVbo = 0;    int m_quadVerts = 0; // blob shadow

    // Offscreen target for resolution scaling
    GLuint m_fbo = 0, m_fboTex = 0, m_fboDepth = 0;
    int m_rtW = 0, m_rtH = 0;
    bool m_usingFbo = false;

    // Simulation state (tech-demo scene)
    Vec3  m_playerPos{0.0f, 0.0f, 6.0f};
    float m_playerYaw = 3.14159f;
    float m_velY = 0.0f;
    bool  m_grounded = true;
    float m_walkPhase = 0.0f;
    float m_moveBlend = 0.0f;
    float m_camYaw = 3.14159f;
    float m_camPitch = 0.38f;
    float m_sinceCamTouch = 10.0f;
    float m_nodTimer = 0.0f;
    bool  m_prevJump = false;
    bool  m_prevInteract = false;
    Vec3  m_lightDir{0.5f, 0.8f, 0.3f};
    float m_skyR = 0.45f, m_skyG = 0.70f, m_skyB = 0.95f;
};

} // namespace ob
