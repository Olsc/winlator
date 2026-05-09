#include <malloc.h>
#include <assert.h>
#include <string.h>
#include <math.h>
#include "engine.h"
#include "math.h"
#include "renderer.h"
#include "input.h"
#include <GLES2/gl2.h>

static const char* rayVertexShader =
    "attribute vec3 a_Position;\n"
    "uniform mat4 u_MVP;\n"
    "void main() {\n"
    "    gl_Position = u_MVP * vec4(a_Position, 1.0);\n"
    "}\n";

static const char* rayFragmentShader =
    "precision mediump float;\n"
    "void main() {\n"
    "    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);\n"
    "}\n";

static void InitRayShader(struct XrRenderer* renderer) {
    if (renderer->RayProgram != 0 || renderer->RayMVPLocation == -2) return;
    
    GLint status;
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &rayVertexShader, NULL);
    glCompileShader(vs);
    glGetShaderiv(vs, GL_COMPILE_STATUS, &status);
    if (!status) {
        renderer->RayMVPLocation = -2; // Mark as failed
        return;
    }

    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &rayFragmentShader, NULL);
    glCompileShader(fs);
    glGetShaderiv(fs, GL_COMPILE_STATUS, &status);
    if (!status) {
        renderer->RayMVPLocation = -2;
        return;
    }

    renderer->RayProgram = glCreateProgram();
    glAttachShader(renderer->RayProgram, vs);
    glAttachShader(renderer->RayProgram, fs);
    glLinkProgram(renderer->RayProgram);
    glGetProgramiv(renderer->RayProgram, GL_LINK_STATUS, &status);
    if (!status) {
        renderer->RayProgram = 0;
        renderer->RayMVPLocation = -2;
        return;
    }

    renderer->RayMVPLocation = glGetUniformLocation(renderer->RayProgram, "u_MVP");
    renderer->RayPosLocation = glGetAttribLocation(renderer->RayProgram, "a_Position");
}

#define DECL_PFN(pfn) PFN_##pfn pfn = NULL
#define INIT_PFN(pfn) OXR(xrGetInstanceProcAddr(engine->Instance, #pfn, (PFN_xrVoidFunction*)(&pfn)))

DECL_PFN(xrCreatePassthroughFB);
DECL_PFN(xrDestroyPassthroughFB);
DECL_PFN(xrPassthroughStartFB);
DECL_PFN(xrPassthroughPauseFB);
DECL_PFN(xrCreatePassthroughLayerFB);
DECL_PFN(xrDestroyPassthroughLayerFB);
DECL_PFN(xrPassthroughLayerPauseFB);
DECL_PFN(xrPassthroughLayerResumeFB);

void XrRendererInit(struct XrEngine* engine, struct XrRenderer* renderer)
{
    if (renderer->Initialized)
    {
        XrRendererDestroy(engine, renderer);
    }
    memset(renderer, 0, sizeof(struct XrRenderer));
    renderer->InvertedViewPose[0].orientation.w = 1.0f;
    renderer->InvertedViewPose[1].orientation.w = 1.0f;
    renderer->HmdOrientation.x = 0;
    renderer->HmdOrientation.y = 0;
    renderer->HmdOrientation.z = 0;

    if (engine->PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH])
    {
        INIT_PFN(xrCreatePassthroughFB);
        INIT_PFN(xrDestroyPassthroughFB);
        INIT_PFN(xrPassthroughStartFB);
        INIT_PFN(xrPassthroughPauseFB);
        INIT_PFN(xrCreatePassthroughLayerFB);
        INIT_PFN(xrDestroyPassthroughLayerFB);
        INIT_PFN(xrPassthroughLayerPauseFB);
        INIT_PFN(xrPassthroughLayerResumeFB);
    }

    int eyeW, eyeH;
    XrRendererGetResolution(engine, renderer, &eyeW, &eyeH);
    renderer->ConfigInt[CONFIG_VIEWPORT_WIDTH] = eyeW;
    renderer->ConfigInt[CONFIG_VIEWPORT_HEIGHT] = eyeH;

    // Get the viewport configuration info for the chosen viewport configuration type.
    renderer->ViewportConfig.type = XR_TYPE_VIEW_CONFIGURATION_PROPERTIES;
    OXR(xrGetViewConfigurationProperties(engine->Instance, engine->SystemId,
                                         XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                                         &renderer->ViewportConfig));

    uint32_t num_spaces = 0;
    OXR(xrEnumerateReferenceSpaces(engine->Session, 0, &num_spaces, NULL));
    XrReferenceSpaceType* spaces = (XrReferenceSpaceType*)malloc(num_spaces * sizeof(XrReferenceSpaceType));
    OXR(xrEnumerateReferenceSpaces(engine->Session, num_spaces, &num_spaces, spaces));

    for (uint32_t i = 0; i < num_spaces; i++)
    {
        if (spaces[i] == XR_REFERENCE_SPACE_TYPE_STAGE)
        {
            renderer->StageSupported = true;
            break;
        }
    }

    free(spaces);

    if (engine->CurrentSpace == XR_NULL_HANDLE)
    {
        XrRendererRecenter(engine, renderer);
    }

    renderer->Projections = (XrView*)malloc(sizeof(XrView) * XrMaxNumEyes);
    for (int i = 0; i < XrMaxNumEyes; i++)
    {
        renderer->Projections[i].type = XR_TYPE_VIEW;
        renderer->Projections[i].next = NULL;
        renderer->Projections[i].pose.orientation.w = 1.0f;
    }

    // Create framebuffers later after ViewConfig is populated

    if (engine->PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH])
    {
        XrPassthroughCreateInfoFB ptci = {XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
        XrResult result;
        OXR(result = xrCreatePassthroughFB(engine->Session, &ptci, &renderer->Passthrough));

        if (XR_SUCCEEDED(result))
        {
            XrPassthroughLayerCreateInfoFB plci = {XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
            plci.passthrough = renderer->Passthrough;
            plci.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
            OXR(xrCreatePassthroughLayerFB(engine->Session, &plci, &renderer->PassthroughLayer));
        }

        OXR(xrPassthroughStartFB(renderer->Passthrough));
        OXR(xrPassthroughLayerResumeFB(renderer->PassthroughLayer));
    }

    // Create eye framebuffers.
    int width = renderer->ViewConfig[0].recommendedImageRectWidth;
    int height = renderer->ViewConfig[0].recommendedImageRectHeight;
    for (int i = 0; i < XrMaxNumEyes; i++)
    {
        XrFramebufferCreate(&renderer->Framebuffer[i], engine->Session, width, height);
    }

    // Create screen framebuffer with a fixed 16:9 aspect ratio for the Wine window.
    XrFramebufferCreate(&renderer->ScreenFramebuffer, engine->Session, 1280, 720);

    renderer->Initialized = true;
}

void XrRendererDestroy(struct XrEngine* engine, struct XrRenderer* renderer)
{
    if (engine->PlatformFlag[PLATFORM_EXTENSION_PASSTHROUGH])
    {
        if (renderer->PassthroughRunning)
        {
            OXR(xrPassthroughLayerPauseFB(renderer->PassthroughLayer));
        }
        OXR(xrPassthroughPauseFB(renderer->Passthrough));
        OXR(xrDestroyPassthroughFB(renderer->Passthrough));
        renderer->Passthrough = XR_NULL_HANDLE;
    }

    for (int i = 0; i < XrMaxNumEyes; i++)
    {
        XrFramebufferDestroy(&renderer->Framebuffer[i]);
    }
    XrFramebufferDestroy(&renderer->ScreenFramebuffer);
    free(renderer->Projections);
    renderer->Initialized = false;
}


void XrRendererGetResolution(struct XrEngine* engine, struct XrRenderer* renderer, int* pWidth, int* pHeight)
{
    static int width = 0;
    static int height = 0;

    if (engine)
    {
        // Enumerate the viewport configurations.
        uint32_t viewport_config_count = 0;
        OXR(xrEnumerateViewConfigurations(engine->Instance, engine->SystemId, 0,
                                          &viewport_config_count, NULL));

        XrViewConfigurationType* viewport_configs =
                (XrViewConfigurationType*)malloc(viewport_config_count * sizeof(XrViewConfigurationType));

        OXR(xrEnumerateViewConfigurations(engine->Instance, engine->SystemId,
                                          viewport_config_count, &viewport_config_count,
                                          viewport_configs));

        for (uint32_t i = 0; i < viewport_config_count; i++)
        {
            const XrViewConfigurationType viewport_config_type = viewport_configs[i];

            ALOGV("Viewport configuration type %d", (int)viewport_config_type);

            XrViewConfigurationProperties viewport_config;
            viewport_config.type = XR_TYPE_VIEW_CONFIGURATION_PROPERTIES;
            OXR(xrGetViewConfigurationProperties(engine->Instance, engine->SystemId,
                                                 viewport_config_type, &viewport_config));

            uint32_t view_count;
            OXR(xrEnumerateViewConfigurationViews(engine->Instance, engine->SystemId,
                                                  viewport_config_type, 0, &view_count, NULL));

            if (view_count > 0)
            {
                XrViewConfigurationView* elements =
                        (XrViewConfigurationView*)malloc(view_count * sizeof(XrViewConfigurationView));

                for (uint32_t e = 0; e < view_count; e++)
                {
                    elements[e].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
                    elements[e].next = NULL;
                }

                OXR(xrEnumerateViewConfigurationViews(engine->Instance, engine->SystemId,
                                                      viewport_config_type, view_count, &view_count,
                                                      elements));

                // Cache the view config properties for the selected config type.
                if (viewport_config_type == XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
                {
                    assert(view_count == XrMaxNumEyes);
                    for (uint32_t e = 0; e < view_count; e++)
                    {
                        renderer->ViewConfig[e] = elements[e];
                    }
                }
 
                free(elements);
            }
            else
            {
                ALOGE("Empty viewport configuration");
            }
        }

        free(viewport_configs);

        *pWidth = width = renderer->ViewConfig[0].recommendedImageRectWidth;
        *pHeight = height = renderer->ViewConfig[0].recommendedImageRectHeight;
    }
    else
    {
        // use cached values
        *pWidth = width;
        *pHeight = height;
    }
}

bool XrRendererInitFrame(struct XrEngine* engine, struct XrRenderer* renderer)
{
    if (!renderer->Initialized)
    {
        return false;
    }
    XrRendererHandleXrEvents(engine, renderer);
    if (!renderer->SessionActive)
    {
        return false;
    }

    XrEngineWaitForFrame(engine);

    XrFrameBeginInfo begin_frame_info = {XR_TYPE_FRAME_BEGIN_INFO, NULL};
    OXR(xrBeginFrame(engine->Session, &begin_frame_info));

    if (!renderer->SessionVisible)
    {
        renderer->LayerCount = 0;
        return true;
    }

    // Update passthrough
    if (renderer->PassthroughRunning != renderer->ConfigInt[CONFIG_PASSTHROUGH])
    {
        if (renderer->ConfigInt[CONFIG_PASSTHROUGH])
        {
            OXR(xrPassthroughLayerResumeFB(renderer->PassthroughLayer));
        }
        else
        {
            OXR(xrPassthroughLayerPauseFB(renderer->PassthroughLayer));
        }
        renderer->PassthroughRunning = renderer->ConfigInt[CONFIG_PASSTHROUGH];
    }

    XrViewLocateInfo projection_info = {XR_TYPE_VIEW_LOCATE_INFO, NULL};
    projection_info.viewConfigurationType = renderer->ViewportConfig.viewConfigurationType;
    projection_info.displayTime = engine->PredictedDisplayTime;
    projection_info.space = engine->CurrentSpace;

    XrViewState view_state = {XR_TYPE_VIEW_STATE, NULL};
    uint32_t projection_count = XrMaxNumEyes;

    XrResult res = xrLocateViews(engine->Session, &projection_info, &view_state, projection_count,
                                 &projection_count, renderer->Projections);

    if (res == XR_SUCCESS && (view_state.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT))
    {
        renderer->Fov.angleLeft = 0;
        renderer->Fov.angleRight = 0;
        renderer->Fov.angleUp = 0;
        renderer->Fov.angleDown = 0;
        for (int eye = 0; eye < XrMaxNumEyes; eye++)
        {
            renderer->Fov.angleLeft += renderer->Projections[eye].fov.angleLeft / 2.0f;
            renderer->Fov.angleRight += renderer->Projections[eye].fov.angleRight / 2.0f;
            renderer->Fov.angleUp += renderer->Projections[eye].fov.angleUp / 2.0f;
            renderer->Fov.angleDown += renderer->Projections[eye].fov.angleDown / 2.0f;
            renderer->InvertedViewPose[eye] = renderer->Projections[eye].pose;
        }

        renderer->HmdOrientation = XrQuaternionfEulerAngles(renderer->InvertedViewPose[0].orientation);
        renderer->LayerCount = 0;
    }
    else
    {
        renderer->LayerCount = 0;
    }

    return true;
}

void XrRendererBeginScreen(struct XrRenderer* renderer)
{
    XrFramebufferAcquire(&renderer->ScreenFramebuffer);
}

void XrRendererEndScreen(struct XrRenderer* renderer)
{
    XrFramebufferRelease(&renderer->ScreenFramebuffer);
}

void XrRendererBeginFrame(struct XrRenderer* renderer, int fbo_index)
{
    renderer->ConfigInt[CONFIG_CURRENT_FBO] = fbo_index;
    XrFramebufferAcquire(&renderer->Framebuffer[fbo_index]);
}

void XrRendererEndFrame(struct XrRenderer* renderer, struct XrInput* input)
{
    int fbo_index = renderer->ConfigInt[CONFIG_CURRENT_FBO];

    // Draw controller rays
    if (renderer->SessionVisible && renderer->RayMVPLocation != -2) {
        InitRayShader(renderer);
        if (renderer->RayProgram != 0) {
            float projection[16];
            Matrix4f_CreateProjectionFov(projection, renderer->Projections[fbo_index].fov, 0.1f, 100.0f);

            float view[16];
            for (int i = 0; i < 16; i++) view[i] = (i % 5 == 0) ? 1.0f : 0.0f; // Identity

            float eyeMatrix[16];
            XrQuaternionfToMatrix4f(&renderer->Projections[fbo_index].pose.orientation, eyeMatrix);
            eyeMatrix[12] = renderer->Projections[fbo_index].pose.position.x;
            eyeMatrix[13] = renderer->Projections[fbo_index].pose.position.y;
            eyeMatrix[14] = renderer->Projections[fbo_index].pose.position.z;
            Matrix4f_Invert(view, eyeMatrix);

            float vp[16];
            Matrix4f_Multiply(vp, projection, view);

            // Save current GL state
            GLint prevProgram, prevBuffer, prevViewport[4];
            glGetIntegerv(GL_CURRENT_PROGRAM, &prevProgram);
            glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prevBuffer);
            glGetIntegerv(GL_VIEWPORT, prevViewport);
            GLboolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
            GLboolean prevBlend = glIsEnabled(GL_BLEND);

            glUseProgram(renderer->RayProgram);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glViewport(0, 0, renderer->Framebuffer[fbo_index].Width, renderer->Framebuffer[fbo_index].Height);

            for (int i = 0; i < 2; i++) {
                XrPosef pose = XrInputGetPose(input, i);
                if (pose.orientation.w == 0 && pose.orientation.x == 0) continue; // Skip invalid poses

                float model[16];
                XrQuaternionfToMatrix4f(&pose.orientation, model);
                model[12] = pose.position.x;
                model[13] = pose.position.y;
                model[14] = pose.position.z;

                float mvp[16];
                Matrix4f_Multiply(mvp, vp, model);
                glUniformMatrix4fv(renderer->RayMVPLocation, 1, GL_FALSE, mvp);

                float vertices[] = {
                    0, 0, 0,
                    0, 0, -5.0f // 5 meters long
                };
                glEnableVertexAttribArray(renderer->RayPosLocation);
                glVertexAttribPointer(renderer->RayPosLocation, 3, GL_FLOAT, GL_FALSE, 0, vertices);
                glLineWidth(5.0f);
                glDrawArrays(GL_LINES, 0, 2);
                glDisableVertexAttribArray(renderer->RayPosLocation);
            }

            // Restore GL state
            glUseProgram(prevProgram);
            glBindBuffer(GL_ARRAY_BUFFER, prevBuffer);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            if (prevDepthTest) glEnable(GL_DEPTH_TEST);
            if (prevBlend) glEnable(GL_BLEND);
        }
    }

    XrFramebufferRelease(&renderer->Framebuffer[fbo_index]);
}

void XrRendererFinishFrame(struct XrEngine* engine, struct XrRenderer* renderer, struct XrInput* input)
{
    if (!renderer->SessionActive)
    {
        return;
    }

    if (!renderer->SessionVisible)
    {
        XrFrameEndInfo end_frame_info = {XR_TYPE_FRAME_END_INFO, NULL};
        end_frame_info.type = XR_TYPE_FRAME_END_INFO;
        end_frame_info.displayTime = engine->PredictedDisplayTime;
        end_frame_info.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        end_frame_info.layerCount = 0;
        OXR(xrEndFrame(engine->Session, &end_frame_info));
        return;
    }

    int x = 0;
    int y = 0;
    int w = renderer->Framebuffer[0].Width;
    int h = renderer->Framebuffer[0].Height;
    if (renderer->ConfigInt[CONFIG_SBS])
    {
        w /= 2;
    }

    int mode = renderer->ConfigInt[CONFIG_MODE];
    XrCompositionLayerProjectionView projection_layer_elements[2] = {};
    
    // Add Projection Layer (3D controllers/rays)
    renderer->ConfigFloat[CONFIG_MENU_YAW] = renderer->HmdOrientation.y;
    for (int eye = 0; eye < XrMaxNumEyes; eye++)
    {
        int eye_x = 0;
        struct XrFramebuffer* framebuffer = &renderer->Framebuffer[0];
        XrPosef pose = renderer->InvertedViewPose[0];

        if (renderer->ConfigInt[CONFIG_SBS]) {
            if (eye == 1) eye_x = w;
        } else if (mode != RENDER_MODE_MONO_SCREEN && mode != RENDER_MODE_MONO_6DOF) {
            framebuffer = &renderer->Framebuffer[eye];
            pose = renderer->InvertedViewPose[eye];
        }
        
        XrVector3f roll_axis = {0, 0, 1};
        XrVector3f rotation = XrQuaternionfEulerAngles(pose.orientation);
        XrQuaternionf invRoll = XrQuaternionfCreateFromVectorAngle(roll_axis, ToRadians(rotation.z));
        pose.orientation = XrQuaternionfMultiply(pose.orientation, invRoll);

        memset(&projection_layer_elements[eye], 0, sizeof(XrCompositionLayerProjectionView));
        projection_layer_elements[eye].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
        projection_layer_elements[eye].pose = pose;
        projection_layer_elements[eye].fov = renderer->Projections[eye].fov;

        memset(&projection_layer_elements[eye].subImage, 0, sizeof(XrSwapchainSubImage));
        projection_layer_elements[eye].subImage.swapchain = framebuffer->Handle;
        projection_layer_elements[eye].subImage.imageRect.offset.x = eye_x;
        projection_layer_elements[eye].subImage.imageRect.offset.y = 0;
        projection_layer_elements[eye].subImage.imageRect.extent.width = w;
        projection_layer_elements[eye].subImage.imageRect.extent.height = h;
        projection_layer_elements[eye].subImage.imageArrayIndex = 0;
    }

    XrCompositionLayerProjection projection_layer = {};
    projection_layer.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
    projection_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
    projection_layer.space = engine->CurrentSpace;
    projection_layer.viewCount = XrMaxNumEyes;
    projection_layer.views = projection_layer_elements;
    renderer->Layers[renderer->LayerCount++].projection = projection_layer;

    // Add Quad Layer (Wine Screen) for Screen modes
    if ((mode == RENDER_MODE_MONO_SCREEN) || (mode == RENDER_MODE_STEREO_SCREEN))
    {
        float distance = 2.0f; // Fixed distance of 2 meters as requested
        // Use the captured height from the last recenter to ensure the window is at eye level and level with gravity.
        XrVector3f pos = {0, renderer->RecenterHeight, -distance};
        
        XrCompositionLayerQuad quad_layer = {};
        quad_layer.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
        quad_layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        quad_layer.space = engine->FakeSpace; // Use FakeSpace for guaranteed level orientation
        memset(&quad_layer.subImage, 0, sizeof(XrSwapchainSubImage));
        quad_layer.subImage.imageRect.offset.x = 0;
        quad_layer.subImage.imageRect.offset.y = 0;
        quad_layer.subImage.imageRect.extent.width = renderer->ScreenFramebuffer.Width;
        quad_layer.subImage.imageRect.extent.height = renderer->ScreenFramebuffer.Height;
        quad_layer.subImage.swapchain = renderer->ScreenFramebuffer.Handle;
        
        // Orientation is identity (facing forward in the recentered space)
        quad_layer.pose.orientation.w = 1.0f;
        quad_layer.pose.position = pos;
        
        // Use the aspect ratio passed from Java (XServer resolution)
        quad_layer.size.width = 4.0f;
        quad_layer.size.height = 4.0f / renderer->ScreenAspectRatio;

        quad_layer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
        renderer->Layers[renderer->LayerCount++].quad = quad_layer;
    }

    // Compose the layers for this frame.
    const XrCompositionLayerBaseHeader* layers[XrMaxLayerCount] = {};
    for (int i = 0; i < renderer->LayerCount; i++)
    {
        layers[i] = (const XrCompositionLayerBaseHeader*)&renderer->Layers[i];
    }

    XrFrameEndInfo end_frame_info = {};
    end_frame_info.type = XR_TYPE_FRAME_END_INFO;
    end_frame_info.displayTime = engine->PredictedDisplayTime;
    end_frame_info.environmentBlendMode = renderer->PassthroughRunning ? XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND : XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    end_frame_info.layerCount = renderer->LayerCount;
    end_frame_info.layers = layers;
    OXR(xrEndFrame(engine->Session, &end_frame_info));
}

void XrRendererBindFramebuffer(struct XrRenderer* renderer)
{
    if (!renderer->Initialized)
        return;
    int fbo_index = renderer->ConfigInt[CONFIG_CURRENT_FBO];
    XrFramebufferSetCurrent(&renderer->Framebuffer[fbo_index]);
}

void XrRendererBindScreenFramebuffer(struct XrRenderer* renderer)
{
    if (!renderer->Initialized)
        return;
    XrFramebufferSetCurrent(&renderer->ScreenFramebuffer);
}


void XrRendererRecenter(struct XrEngine* engine, struct XrRenderer* renderer)
{
    // Calculate recenter reference
    XrReferenceSpaceCreateInfo space_info = {};
    space_info.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    space_info.poseInReferenceSpace.orientation.w = 1.0f;
    if (engine->CurrentSpace != XR_NULL_HANDLE)
    {
        XrSpaceLocation loc = {};
        loc.type = XR_TYPE_SPACE_LOCATION;
        OXR(xrLocateSpace(engine->HeadSpace, engine->CurrentSpace,
                          engine->PredictedDisplayTime, &loc));
        renderer->HmdOrientation = XrQuaternionfEulerAngles(loc.pose.orientation);
        renderer->RecenterHeight = loc.pose.position.y;

        renderer->ConfigFloat[CONFIG_RECENTER_YAW] += renderer->HmdOrientation.y;
        float renceter_yaw = ToRadians(renderer->ConfigFloat[CONFIG_RECENTER_YAW]);
        space_info.poseInReferenceSpace.orientation.x = 0;
        space_info.poseInReferenceSpace.orientation.y = sinf(renceter_yaw / 2);
        space_info.poseInReferenceSpace.orientation.z = 0;
        space_info.poseInReferenceSpace.orientation.w = cosf(renceter_yaw / 2);
    }

    // Delete previous space instances
    if (engine->StageSpace != XR_NULL_HANDLE)
    {
        OXR(xrDestroySpace(engine->StageSpace));
    }
    if (engine->FakeSpace != XR_NULL_HANDLE)
    {
        OXR(xrDestroySpace(engine->FakeSpace));
    }

    // Create a default stage space to use if SPACE_TYPE_STAGE is not
    // supported, or calls to xrGetReferenceSpaceBoundsRect fail.
    space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    memset(&space_info.poseInReferenceSpace, 0, sizeof(XrPosef));
    space_info.poseInReferenceSpace.orientation.w = 1.0;
    if (engine->PlatformFlag[PLATFORM_TRACKING_FLOOR])
    {
        space_info.poseInReferenceSpace.position.y = -1.6750f;
    }
    OXR(xrCreateReferenceSpace(engine->Session, &space_info, &engine->FakeSpace));
    ALOGV("Created fake stage space from local space with offset");
    engine->CurrentSpace = engine->FakeSpace;

    if (renderer->StageSupported)
    {
        space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
        memset(&space_info.poseInReferenceSpace, 0, sizeof(XrPosef));
        space_info.poseInReferenceSpace.orientation.w = 1.0;
        OXR(xrCreateReferenceSpace(engine->Session, &space_info, &engine->StageSpace));
        ALOGV("Created stage space");
        if (engine->PlatformFlag[PLATFORM_TRACKING_FLOOR])
        {
            engine->CurrentSpace = engine->StageSpace;
        }
    }

    // Update menu orientation
    renderer->ConfigFloat[CONFIG_MENU_PITCH] = renderer->HmdOrientation.x;
    renderer->ConfigFloat[CONFIG_MENU_YAW] = 0.0f;
}

void XrRendererHandleSessionStateChanges(struct XrEngine* engine, struct XrRenderer* renderer, XrSessionState state)
{
    if (state == XR_SESSION_STATE_READY)
    {
        assert(renderer->SessionActive == false);

        XrSessionBeginInfo session_begin_info;
        memset(&session_begin_info, 0, sizeof(session_begin_info));
        session_begin_info.type = XR_TYPE_SESSION_BEGIN_INFO;
        session_begin_info.next = NULL;
        session_begin_info.primaryViewConfigurationType = renderer->ViewportConfig.viewConfigurationType;

        XrResult result;
        OXR(result = xrBeginSession(engine->Session, &session_begin_info));
        renderer->SessionActive = (result == XR_SUCCESS);
        if (!renderer->SessionActive) ALOGE("Failed to begin XR session: %d", (int)result);
        ALOGV("Session active = %d", renderer->SessionActive);

#ifdef ANDROID
        if (renderer->SessionActive && engine->PlatformFlag[PLATFORM_EXTENSION_PERFORMANCE])
        {
            PFN_xrPerfSettingsSetPerformanceLevelEXT pfnPerfSettingsSetPerformanceLevelEXT = NULL;
            OXR(xrGetInstanceProcAddr(engine->Instance, "xrPerfSettingsSetPerformanceLevelEXT",
                                      (PFN_xrVoidFunction*)(&pfnPerfSettingsSetPerformanceLevelEXT)));

            OXR(pfnPerfSettingsSetPerformanceLevelEXT(
                    engine->Session, XR_PERF_SETTINGS_DOMAIN_CPU_EXT, XR_PERF_SETTINGS_LEVEL_BOOST_EXT));
            OXR(pfnPerfSettingsSetPerformanceLevelEXT(
                    engine->Session, XR_PERF_SETTINGS_DOMAIN_GPU_EXT, XR_PERF_SETTINGS_LEVEL_BOOST_EXT));

            PFN_xrSetAndroidApplicationThreadKHR pfnSetAndroidApplicationThreadKHR = NULL;
            OXR(xrGetInstanceProcAddr(engine->Instance, "xrSetAndroidApplicationThreadKHR",
                                      (PFN_xrVoidFunction*)(&pfnSetAndroidApplicationThreadKHR)));

            OXR(pfnSetAndroidApplicationThreadKHR(engine->Session,
                                                  XR_ANDROID_THREAD_TYPE_APPLICATION_MAIN_KHR,
                                                  engine->MainThreadId));
            OXR(pfnSetAndroidApplicationThreadKHR(engine->Session,
                                                  XR_ANDROID_THREAD_TYPE_RENDERER_MAIN_KHR,
                                                  engine->RenderThreadId));
        }
#endif
        XrRendererUpdateStageBounds(engine, renderer);
    }
    else if (state == XR_SESSION_STATE_STOPPING)
    {
        assert(renderer->SessionActive);

        OXR(xrEndSession(engine->Session));
        renderer->SessionActive = false;
    }
}

void XrRendererHandleXrEvents(struct XrEngine* engine, struct XrRenderer* renderer)
{
    XrEventDataBuffer event_data_bufer = {};

    // Poll for events
    for (;;)
    {
        XrEventDataBaseHeader* base_event_handler = (XrEventDataBaseHeader*)(&event_data_bufer);
        base_event_handler->type = XR_TYPE_EVENT_DATA_BUFFER;
        base_event_handler->next = NULL;
        XrResult r;
        OXR(r = xrPollEvent(engine->Instance, &event_data_bufer));
        if (r != XR_SUCCESS)
        {
            break;
        }

        switch (base_event_handler->type)
        {
            case XR_TYPE_EVENT_DATA_EVENTS_LOST:
                ALOGV("xrPollEvent: received XR_TYPE_EVENT_DATA_EVENTS_LOST");
                break;
            case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
            {
                const XrEventDataInstanceLossPending* instance_loss_pending_event =
                        (XrEventDataInstanceLossPending*)(base_event_handler);
                ALOGV("xrPollEvent: received XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING: time %lf",
                      FromXrTime(instance_loss_pending_event->lossTime));
            }
                break;
            case XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED:
                ALOGV("xrPollEvent: received XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED");
                break;
            case XR_TYPE_EVENT_DATA_PERF_SETTINGS_EXT:
                break;
            case XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING:
                XrRendererRecenter(engine, renderer);
                XrRendererUpdateStageBounds(engine, renderer);
                break;
            case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED:
            {
                const XrEventDataSessionStateChanged* session_state_changed_event =
                        (XrEventDataSessionStateChanged*)(base_event_handler);
                switch (session_state_changed_event->state)
                {
                    case XR_SESSION_STATE_FOCUSED:
                        ALOGV("Session state: FOCUSED");
                        renderer->SessionVisible = true;
                        renderer->SessionFocused = true;
                        XrRendererUpdateStageBounds(engine, renderer);
                        break;
                    case XR_SESSION_STATE_VISIBLE:
                        ALOGV("Session state: VISIBLE");
                        renderer->SessionVisible = true;
                        renderer->SessionFocused = false;
                        break;
                    case XR_SESSION_STATE_SYNCHRONIZED:
                        ALOGV("Session state: SYNCHRONIZED");
                        renderer->SessionVisible = false;
                        renderer->SessionFocused = false;
                        break;
                    case XR_SESSION_STATE_READY:
                        ALOGV("Session state: READY");
                        renderer->SessionVisible = false;
                        renderer->SessionFocused = false;
                        XrRendererHandleSessionStateChanges(engine, renderer, session_state_changed_event->state);
                        break;
                    case XR_SESSION_STATE_STOPPING:
                        ALOGV("Session state: STOPPING");
                        renderer->SessionVisible = false;
                        renderer->SessionFocused = false;
                        XrRendererHandleSessionStateChanges(engine, renderer, session_state_changed_event->state);
                        break;
                    case XR_SESSION_STATE_LOSS_PENDING:
                        ALOGV("Session state: LOSS_PENDING");
                        break;
                    case XR_SESSION_STATE_EXITING:
                        ALOGV("Session state: EXITING");
                        break;
                    default:
                        break;
                }
                break;
            }
            default:
                ALOGV("xrPollEvent: Unknown event");
                break;
        }
    }
}

void XrRendererUpdateStageBounds(struct XrEngine* engine, struct XrRenderer* renderer)
{
    if (!renderer->SessionFocused)
    {
        return;
    }

    XrExtent2Df stage_bounds = {};

    XrResult result;
    OXR(result = xrGetReferenceSpaceBoundsRect(engine->Session, XR_REFERENCE_SPACE_TYPE_STAGE,
                                               &stage_bounds));
    if (result != XR_SUCCESS)
    {
        stage_bounds.width = 1.0f;
        stage_bounds.height = 1.0f;

        engine->CurrentSpace = engine->FakeSpace;
    }
}
