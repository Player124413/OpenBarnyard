#pragma once
// OpenBarnyard Android — minimal 3D math for the native core (column-major
// matrices, OpenGL style). Mat4 multiply has scalar + NEON implementations
// with runtime dispatch (see CpuFeatures). Everything is header-only and
// host-testable; the TOSHI engine has its own math (Toshi/Source/Math) which
// will be used by the full engine port — this module serves the Android
// platform layer / tech demo and proves the optimisation pipeline.
#include <cmath>
#include <cstdint>
#include <cstring>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#  include <arm_neon.h>
#  define OB_HAS_NEON_COMPILE 1
#else
#  define OB_HAS_NEON_COMPILE 0
#endif

namespace ob {

struct Vec3 {
    float x = 0.0f, y = 0.0f, z = 0.0f;
    Vec3() = default;
    Vec3(float px, float py, float pz) : x(px), y(py), z(pz) {}
    Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec3 operator*(float s) const { return {x * s, y * s, z * s}; }
    float Length() const { return std::sqrt(x * x + y * y + z * z); }
    Vec3 Normalized() const {
        const float len = Length();
        return len > 0.000001f ? (*this) * (1.0f / len) : Vec3{0.0f, 1.0f, 0.0f};
    }
};

inline float Dot(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline Vec3 Cross(const Vec3& a, const Vec3& b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

// Column-major 4x4: m[col*4 + row], transforms column vectors (M * v).
struct Mat4 {
    float m[16];

    static Mat4 Identity() {
        Mat4 r{};
        std::memset(r.m, 0, sizeof(r.m));
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.0f;
        return r;
    }
    static Mat4 Translation(float x, float y, float z) {
        Mat4 r = Identity();
        r.m[12] = x; r.m[13] = y; r.m[14] = z;
        return r;
    }
    static Mat4 Scaling(float x, float y, float z) {
        Mat4 r = Identity();
        r.m[0] = x; r.m[5] = y; r.m[10] = z;
        return r;
    }
    static Mat4 RotationY(float radians) {
        Mat4 r = Identity();
        const float c = std::cos(radians), s = std::sin(radians);
        r.m[0] = c;  r.m[2] = -s;
        r.m[8] = s;  r.m[10] = c;
        return r;
    }
    static Mat4 RotationX(float radians) {
        Mat4 r = Identity();
        const float c = std::cos(radians), s = std::sin(radians);
        r.m[5] = c;  r.m[6] = s;
        r.m[9] = -s; r.m[10] = c;
        return r;
    }
    static Mat4 RotationZ(float radians) {
        Mat4 r = Identity();
        const float c = std::cos(radians), s = std::sin(radians);
        r.m[0] = c;  r.m[1] = s;
        r.m[4] = -s; r.m[5] = c;
        return r;
    }
    static Mat4 Perspective(float fovYRadians, float aspect, float zNear, float zFar) {
        Mat4 r{};
        std::memset(r.m, 0, sizeof(r.m));
        const float f = 1.0f / std::tan(fovYRadians * 0.5f);
        r.m[0]  = f / aspect;
        r.m[5]  = f;
        r.m[10] = (zFar + zNear) / (zNear - zFar);
        r.m[11] = -1.0f;
        r.m[14] = (2.0f * zFar * zNear) / (zNear - zFar);
        return r;
    }
    static Mat4 LookAt(const Vec3& eye, const Vec3& center, const Vec3& up) {
        const Vec3 f = (center - eye).Normalized();
        const Vec3 s = Cross(f, up).Normalized();
        const Vec3 u = Cross(s, f);
        Mat4 r = Identity();
        r.m[0] = s.x;  r.m[4] = s.y;  r.m[8]  = s.z;
        r.m[1] = u.x;  r.m[5] = u.y;  r.m[9]  = u.z;
        r.m[2] = -f.x; r.m[6] = -f.y; r.m[10] = -f.z;
        r.m[12] = -Dot(s, eye);
        r.m[13] = -Dot(u, eye);
        r.m[14] =  Dot(f, eye);
        return r;
    }
    Vec3 TransformPoint(const Vec3& p) const {
        const float w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15];
        const float inv = (w != 0.0f) ? 1.0f / w : 1.0f;
        return {(m[0] * p.x + m[4] * p.y + m[8]  * p.z + m[12]) * inv,
                (m[1] * p.x + m[5] * p.y + m[9]  * p.z + m[13]) * inv,
                (m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]) * inv};
    }
};

inline Mat4 MultiplyScalar(const Mat4& a, const Mat4& b) {
    Mat4 r;
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float sum = 0.0f;
            for (int k = 0; k < 4; ++k) sum += a.m[k * 4 + row] * b.m[col * 4 + k];
            r.m[col * 4 + row] = sum;
        }
    }
    return r;
}

#if OB_HAS_NEON_COMPILE
inline Mat4 MultiplyNeon(const Mat4& a, const Mat4& b) {
    Mat4 r;
    const float32x4_t a0 = vld1q_f32(a.m + 0);
    const float32x4_t a1 = vld1q_f32(a.m + 4);
    const float32x4_t a2 = vld1q_f32(a.m + 8);
    const float32x4_t a3 = vld1q_f32(a.m + 12);
    for (int col = 0; col < 4; ++col) {
        const float* bc = b.m + col * 4;
        float32x4_t res = vmulq_lane_f32(a0, vld1_dup_f32(bc + 0), 0);
        res = vmlaq_lane_f32(res, a1, vld1_dup_f32(bc + 1), 0);
        res = vmlaq_lane_f32(res, a2, vld1_dup_f32(bc + 2), 0);
        res = vmlaq_lane_f32(res, a3, vld1_dup_f32(bc + 3), 0);
        vst1q_f32(r.m + col * 4, res);
    }
    return r;
}
#endif

// Set by CpuFeatures.cpp at startup (runtime dispatch). Defaults to scalar.
using Mat4MultiplyFn = Mat4 (*)(const Mat4&, const Mat4&);
extern Mat4MultiplyFn g_mat4Multiply;

inline Mat4 operator*(const Mat4& a, const Mat4& b) { return g_mat4Multiply(a, b); }

} // namespace ob
