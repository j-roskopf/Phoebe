#import "PhoebeProjectMHostView.h"

#define GLES_SILENCE_DEPRECATION 1

#import <OpenGLES/ES3/gl.h>
#import <OpenGLES/ES3/glext.h>
#import <OpenGLES/EAGL.h>
#import <QuartzCore/QuartzCore.h>
#import <TargetConditionals.h>

#include <cstring>
#include <string>
#include <vector>

#include <projectM-4/projectM.h>

@interface PhoebeProjectMHostView ()
@property (nonatomic, strong) EAGLContext *glContext;
@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) GLuint drawableFramebuffer;
@property (nonatomic, assign) GLuint colorRenderbuffer;
@property (nonatomic, assign) GLuint offscreenFramebuffer;
@property (nonatomic, assign) GLuint offscreenColorTexture;
@property (nonatomic, assign) GLuint offscreenDepthStencil;
@property (nonatomic, assign) NSInteger bufferWidth;
@property (nonatomic, assign) NSInteger bufferHeight;
@property (nonatomic, assign) projectm_handle projectM;
@property (nonatomic, assign) BOOL created;
@property (nonatomic, assign) BOOL createFailed;
@property (nonatomic, assign) BOOL playing;
@property (nonatomic, assign) BOOL suspended;
@property (nonatomic, assign) BOOL locked;
@property (nonatomic, assign) BOOL drawableReady;
@property (nonatomic, copy, nullable) NSString *presetPath;
@property (nonatomic, copy, nullable) NSString *presetData;
@property (nonatomic, copy, nullable) NSString *loadedPath;
@property (nonatomic, copy, nullable) NSString *loadedData;
@property (nonatomic, assign) NSInteger frameCount;
@end

@implementation PhoebeProjectMHostView {
    std::vector<float> _pendingPcm;
    int _pendingChannels;
}

+ (Class)layerClass {
    return [CAEAGLLayer class];
}

static void PhoebeClearGlErrors(const char *tag) {
    for (int i = 0; i < 8; i++) {
        GLenum err = glGetError();
        if (err == GL_NO_ERROR) break;
        NSLog(@"[ProjectM] GL error before %s: 0x%04x", tag, err);
    }
}

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (!self) return nil;

    self.opaque = YES;
    self.clipsToBounds = YES;
    self.userInteractionEnabled = NO;
    self.contentScaleFactor = MIN(UIScreen.mainScreen.scale, 2.0);
    self.backgroundColor = UIColor.blackColor;

    CAEAGLLayer *eaglLayer = (CAEAGLLayer *)self.layer;
    eaglLayer.opaque = YES;
    eaglLayer.contentsScale = self.contentScaleFactor;
    eaglLayer.drawableProperties = @{
        kEAGLDrawablePropertyRetainedBacking: @NO,
        kEAGLDrawablePropertyColorFormat: kEAGLColorFormatRGBA8,
    };

    self.playing = YES;
    self.suspended = NO;
    self.locked = NO;
    self.drawableReady = NO;
    self.bufferWidth = 0;
    self.bufferHeight = 0;
    self.drawableFramebuffer = 0;
    self.colorRenderbuffer = 0;
    self.offscreenFramebuffer = 0;
    self.offscreenColorTexture = 0;
    self.offscreenDepthStencil = 0;
    self.projectM = NULL;
    _pendingChannels = 2;

    self.glContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
    if (!self.glContext) {
        NSLog(@"[ProjectM] EAGLContext GLES3 failed");
        self.createFailed = YES;
        return self;
    }
    if (![EAGLContext setCurrentContext:self.glContext]) {
        NSLog(@"[ProjectM] setCurrentContext failed");
        self.createFailed = YES;
        return self;
    }

    self.displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(onDisplayLink:)];
    self.displayLink.preferredFramesPerSecond = 24;
    [self.displayLink addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
    [self applyPaused];
    NSLog(@"[ProjectM] CAEAGLLayer host ready");
    return self;
}

- (void)dealloc {
    [self.displayLink invalidate];
    self.displayLink = nil;
    if (self.glContext) {
        [EAGLContext setCurrentContext:self.glContext];
        [self destroyTargets];
        if (self.projectM) {
            projectm_destroy(self.projectM);
            self.projectM = NULL;
        }
        [EAGLContext setCurrentContext:nil];
    }
}

- (BOOL)isNativeReady {
    return !self.createFailed && self.glContext != nil;
}

- (void)layoutSubviews {
    [super layoutSubviews];
    [self ensureTargets];
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    if (self.window != nil) {
        [self setNeedsLayout];
        [self layoutIfNeeded];
        [self ensureTargets];
    }
}

- (void)setPresetPath:(NSString *)path {
    _presetPath = [path copy];
    _presetData = nil;
    _loadedPath = nil;
}

- (void)setPresetData:(NSString *)data {
    _presetData = [data copy];
    _presetPath = nil;
    _loadedData = nil;
}

- (void)setPresetLocked:(BOOL)locked {
    _locked = locked;
    if (self.projectM) {
        projectm_set_preset_locked(self.projectM, locked);
    }
}

- (void)setPlaying:(BOOL)playing {
    _playing = playing;
    [self applyPaused];
}

- (void)setSuspended:(BOOL)suspended {
    _suspended = suspended;
    [self applyPaused];
}

- (void)addPcmSamples:(const float *)samples count:(NSInteger)count channels:(NSInteger)channels {
    if (!self.playing || samples == NULL || count <= 0) return;
    // Called from the audio processing tap; serialize against drainPcm on the
    // CADisplayLink/render thread so the pending buffer can't be mutated mid-read.
    @synchronized (self) {
        _pendingChannels = (int)MAX(1, MIN(2, channels));
        _pendingPcm.assign(samples, samples + count);
    }
}

- (void)applyPaused {
    self.displayLink.paused = self.suspended || !self.playing || self.createFailed || self.displayLink == nil;
}

- (void)onDisplayLink:(CADisplayLink *)link {
    if (self.suspended || !self.playing || self.createFailed) return;
    [self renderFrame];
}

- (void)destroyTargets {
    if (self.drawableFramebuffer) {
        glDeleteFramebuffers(1, &_drawableFramebuffer);
        self.drawableFramebuffer = 0;
    }
    if (self.colorRenderbuffer) {
        glDeleteRenderbuffers(1, &_colorRenderbuffer);
        self.colorRenderbuffer = 0;
    }
    if (self.offscreenFramebuffer) {
        glDeleteFramebuffers(1, &_offscreenFramebuffer);
        self.offscreenFramebuffer = 0;
    }
    if (self.offscreenColorTexture) {
        glDeleteTextures(1, &_offscreenColorTexture);
        self.offscreenColorTexture = 0;
    }
    if (self.offscreenDepthStencil) {
        glDeleteRenderbuffers(1, &_offscreenDepthStencil);
        self.offscreenDepthStencil = 0;
    }
    self.bufferWidth = 0;
    self.bufferHeight = 0;
    self.drawableReady = NO;
}

- (void)ensureTargets {
    if (self.createFailed || !self.glContext || self.bounds.size.width < 1 || self.bounds.size.height < 1) {
        return;
    }
    if (![EAGLContext setCurrentContext:self.glContext]) return;

    // Cap resolution for mobile GPU cost by lowering the drawable scale. The
    // CAEAGLLayer drawable is always bounds * contentsScale, so clamping the FBO
    // size on its own would leave the requested size permanently disagreeing with
    // the allocated size — and the early-out below would then rebuild every frame.
    CGFloat scale = MIN(UIScreen.mainScreen.scale, 2.0);
    const CGFloat maxEdge = 720.0;
    CGFloat longEdgePx = MAX(self.bounds.size.width, self.bounds.size.height) * scale;
    if (longEdgePx > maxEdge) {
        scale *= maxEdge / longEdgePx;
    }

    NSInteger width = MAX(2, (NSInteger)llround(self.bounds.size.width * scale));
    NSInteger height = MAX(2, (NSInteger)llround(self.bounds.size.height * scale));
    if (self.drawableReady && llabs(width - self.bufferWidth) < 4 && llabs(height - self.bufferHeight) < 4) {
        return;
    }

    [self destroyTargets];
    PhoebeClearGlErrors("destroy");

    CAEAGLLayer *eaglLayer = (CAEAGLLayer *)self.layer;
    if (self.contentScaleFactor != scale) {
        self.contentScaleFactor = scale;
    }
    eaglLayer.contentsScale = scale;

    // --- Drawable FBO (CAEAGLLayer present target) ---
    GLuint colorRb = 0;
    glGenRenderbuffers(1, &colorRb);
    glBindRenderbuffer(GL_RENDERBUFFER, colorRb);
    if (![self.glContext renderbufferStorage:GL_RENDERBUFFER fromDrawable:eaglLayer]) {
        NSLog(@"[ProjectM] renderbufferStorage:fromDrawable failed");
        glDeleteRenderbuffers(1, &colorRb);
        return;
    }
    GLint rbWidth = 0;
    GLint rbHeight = 0;
    glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_WIDTH, &rbWidth);
    glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_HEIGHT, &rbHeight);
    if (rbWidth < 2 || rbHeight < 2) {
        NSLog(@"[ProjectM] drawable size invalid %dx%d", rbWidth, rbHeight);
        glDeleteRenderbuffers(1, &colorRb);
        return;
    }

    GLuint drawableFbo = 0;
    glGenFramebuffers(1, &drawableFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, drawableFbo);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colorRb);
    GLenum drawableStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (drawableStatus != GL_FRAMEBUFFER_COMPLETE) {
        NSLog(@"[ProjectM] drawable FBO incomplete: 0x%04x", drawableStatus);
        glDeleteFramebuffers(1, &drawableFbo);
        glDeleteRenderbuffers(1, &colorRb);
        return;
    }

    // --- Offscreen texture FBO for projectM (needs stencil, like desktop) ---
    // Match drawable size so blit is 1:1.
    width = rbWidth;
    height = rbHeight;

    GLuint colorTex = 0;
    glGenTextures(1, &colorTex);
    glBindTexture(GL_TEXTURE_2D, colorTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)width, (GLsizei)height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);

    GLuint depthStencil = 0;
    glGenRenderbuffers(1, &depthStencil);
    glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, (GLsizei)width, (GLsizei)height);

    GLuint offscreenFbo = 0;
    glGenFramebuffers(1, &offscreenFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, offscreenFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
    GLenum offscreenStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (offscreenStatus != GL_FRAMEBUFFER_COMPLETE) {
        NSLog(@"[ProjectM] offscreen FBO incomplete: 0x%04x", offscreenStatus);
        glDeleteFramebuffers(1, &offscreenFbo);
        glDeleteFramebuffers(1, &drawableFbo);
        glDeleteTextures(1, &colorTex);
        glDeleteRenderbuffers(1, &depthStencil);
        glDeleteRenderbuffers(1, &colorRb);
        return;
    }

    self.drawableFramebuffer = drawableFbo;
    self.colorRenderbuffer = colorRb;
    self.offscreenFramebuffer = offscreenFbo;
    self.offscreenColorTexture = colorTex;
    self.offscreenDepthStencil = depthStencil;
    self.bufferWidth = width;
    self.bufferHeight = height;
    self.drawableReady = YES;
    if (self.projectM) {
        projectm_set_window_size(self.projectM, (size_t)width, (size_t)height);
    }
    NSLog(@"[ProjectM] targets ready drawable+offscreen %ldx%ld", (long)width, (long)height);
}

- (void)ensureProjectM {
    if (self.created || self.createFailed) return;
    self.created = YES;
    self.projectM = projectm_create();
    if (!self.projectM) {
        NSLog(@"[ProjectM] projectm_create failed");
        self.createFailed = YES;
        [self applyPaused];
        return;
    }
    projectm_set_fps(self.projectM, 24);
    projectm_set_mesh_size(self.projectM, 32, 24);
    projectm_set_preset_duration(self.projectM, 20.0);
    projectm_set_window_size(self.projectM, (size_t)MAX(2, self.bufferWidth), (size_t)MAX(2, self.bufferHeight));
    projectm_set_preset_locked(self.projectM, self.locked);
    [self applyPresetForce:YES];
    NSLog(@"[ProjectM] projectm_create OK (texture FBO + CAEAGL blit)");
}

- (void)applyPresetForce:(BOOL)force {
    if (!self.projectM) return;
    if (self.presetPath.length > 0 && (force || ![self.presetPath isEqualToString:self.loadedPath])) {
        projectm_load_preset_file(self.projectM, self.presetPath.UTF8String, false);
        self.loadedPath = self.presetPath;
        self.loadedData = nil;
        return;
    }
    if (self.presetData.length > 0 && (force || ![self.presetData isEqualToString:self.loadedData])) {
        projectm_load_preset_data(self.projectM, self.presetData.UTF8String, false);
        self.loadedData = self.presetData;
        self.loadedPath = nil;
        return;
    }
    if (force && self.presetPath.length == 0 && self.presetData.length == 0) {
        projectm_load_preset_file(self.projectM, "idle://", false);
        self.loadedPath = @"idle://";
        self.loadedData = nil;
    }
}

- (void)drainPcm {
    if (!self.projectM) return;
    std::vector<float> pcm;
    int pendingChannels = 1;
    @synchronized (self) {
        if (_pendingPcm.empty()) return;
        pcm.swap(_pendingPcm);
        pendingChannels = _pendingChannels;
    }
    const auto channels = pendingChannels >= 2 ? PROJECTM_STEREO : PROJECTM_MONO;
    unsigned int frames = channels == PROJECTM_STEREO
        ? (unsigned int)(pcm.size() / 2)
        : (unsigned int)pcm.size();
    if (frames == 0) return;
    projectm_pcm_add_float(self.projectM, pcm.data(), frames, channels);
}

- (void)renderFrame {
    [self ensureTargets];
    if (!self.drawableReady || self.offscreenFramebuffer == 0 || self.drawableFramebuffer == 0) return;
    if (![EAGLContext setCurrentContext:self.glContext]) return;

    PhoebeClearGlErrors("frame-start");

    // 1) projectM → offscreen texture FBO (desktop-equivalent)
    glBindFramebuffer(GL_FRAMEBUFFER, self.offscreenFramebuffer);
    [self ensureProjectM];
    if (!self.projectM) return;

    [self drainPcm];
    [self applyPresetForce:NO];

    projectm_set_window_size(self.projectM, (size_t)self.bufferWidth, (size_t)self.bufferHeight);
    glViewport(0, 0, (GLsizei)self.bufferWidth, (GLsizei)self.bufferHeight);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    projectm_opengl_render_frame(self.projectM);
    GLenum afterProjectM = glGetError();
    if (afterProjectM != GL_NO_ERROR && (self.frameCount < 3 || self.frameCount % 120 == 0)) {
        NSLog(@"[ProjectM] after render_frame err=0x%04x", afterProjectM);
    }

    // projectM may leave a different FBO bound — restore and blit to the drawable.
    glBindFramebuffer(GL_READ_FRAMEBUFFER, self.offscreenFramebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, self.drawableFramebuffer);
    glBlitFramebuffer(
        0, 0, (GLint)self.bufferWidth, (GLint)self.bufferHeight,
        0, 0, (GLint)self.bufferWidth, (GLint)self.bufferHeight,
        GL_COLOR_BUFFER_BIT,
        GL_NEAREST);
    GLenum afterBlit = glGetError();
    if (afterBlit != GL_NO_ERROR && (self.frameCount < 3 || self.frameCount % 120 == 0)) {
        NSLog(@"[ProjectM] after blit err=0x%04x", afterBlit);
    }

    glBindRenderbuffer(GL_RENDERBUFFER, self.colorRenderbuffer);
    [self.glContext presentRenderbuffer:GL_RENDERBUFFER];

    self.frameCount += 1;
    if (self.frameCount == 1 || self.frameCount % 120 == 0) {
        NSLog(@"[ProjectM] frame=%ld size=%ldx%ld err=0x%04x",
              (long)self.frameCount, (long)self.bufferWidth, (long)self.bufferHeight, glGetError());
    }
}

@end
