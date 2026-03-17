/*
 * vortex_vulkan.h — Vulkan display renderer for VortexEmulator.
 *
 * This is a DISPLAY-ONLY layer: it receives pre-rendered pixel data
 * (from libretro cores via OpenGL ES or software rendering) and
 * presents it to the Android surface using Vulkan.
 *
 * The actual emulation rendering (GLES3) is decoupled from display,
 * improving compatibility on GPUs like Mali Immortalis where GLES3
 * window-surface blitting may have driver issues.
 */
#pragma once

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/native_window.h>
#include <android/log.h>
#include <vector>
#include <cstdint>

class VulkanRenderer {
public:
    VulkanRenderer() = default;
    ~VulkanRenderer() { destroy(); }

    VulkanRenderer(const VulkanRenderer&) = delete;
    VulkanRenderer& operator=(const VulkanRenderer&) = delete;

    /**
     * Initialize Vulkan from an ANativeWindow.
     * @return true if Vulkan is available and ready.
     */
    bool init(ANativeWindow* window, int surfaceW, int surfaceH);

    /** Tear down all Vulkan resources. */
    void destroy();

    /** Recreate swapchain after surface size change. */
    bool surfaceChanged(int width, int height);

    /**
     * Present a frame of pixel data to the screen via Vulkan.
     * @param pixels  Pixel data (uint32_t per pixel)
     * @param w       Frame width
     * @param h       Frame height
     * @param isBGRA  true = XRGB8888 LE (SW render), false = RGBA (HW readback)
     * @param flipY   true = flip vertically (for bottom_left_origin HW cores)
     */
    bool presentFrame(const uint32_t* pixels, unsigned w, unsigned h,
                      bool isBGRA, bool flipY);

    bool isReady() const { return m_ready; }

private:
    bool createInstance();
    bool createSurface(ANativeWindow* window);
    bool pickPhysicalDevice();
    bool createDevice();
    bool createSwapchain(int width, int height);
    bool createSyncObjects();
    bool createCommandPool();
    bool ensureStagingBuffer(VkDeviceSize size);
    bool ensureFrameImage(unsigned w, unsigned h);
    void destroySwapchain();
    void destroyStagingResources();
    void destroyFrameImage();
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags props);
    void transitionImage(VkCommandBuffer cmd, VkImage image,
                         VkImageLayout oldLayout, VkImageLayout newLayout,
                         VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                         VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage);

    bool m_ready = false;

    VkInstance       m_instance       = VK_NULL_HANDLE;
    VkPhysicalDevice m_physicalDevice = VK_NULL_HANDLE;
    VkDevice         m_device         = VK_NULL_HANDLE;
    VkQueue          m_queue          = VK_NULL_HANDLE;
    uint32_t         m_queueFamily    = UINT32_MAX;
    VkSurfaceKHR     m_surface        = VK_NULL_HANDLE;

    // Swapchain
    VkSwapchainKHR          m_swapchain       = VK_NULL_HANDLE;
    VkFormat                m_swapchainFormat  = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D              m_swapchainExtent  = {0, 0};
    std::vector<VkImage>    m_swapchainImages;

    // Command pool & sync
    VkCommandPool   m_cmdPool        = VK_NULL_HANDLE;
    VkCommandBuffer m_cmdBuf         = VK_NULL_HANDLE;
    VkFence         m_fence          = VK_NULL_HANDLE;
    VkSemaphore     m_imageAvailable = VK_NULL_HANDLE;
    VkSemaphore     m_renderFinished = VK_NULL_HANDLE;

    // Staging buffer (host-visible, coherent)
    VkBuffer       m_stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_stagingMemory = VK_NULL_HANDLE;
    VkDeviceSize   m_stagingSize   = 0;
    void*          m_stagingMap    = nullptr;

    // Frame image (device-local, for blit to swapchain)
    VkImage        m_frameImage   = VK_NULL_HANDLE;
    VkDeviceMemory m_frameMemory  = VK_NULL_HANDLE;
    unsigned       m_frameW = 0, m_frameH = 0;
    bool           m_frameImageReady = false;

    VkPhysicalDeviceMemoryProperties m_memProps = {};
    ANativeWindow* m_window = nullptr;
};
